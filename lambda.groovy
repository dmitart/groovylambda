#!/usr/bin/env groovy
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model.*
import com.amazonaws.services.s3.AmazonS3Client
import groovy.grape.Grape
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilationUnit.PrimaryClassNodeOperation
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.tools.GroovyClass

import java.nio.ByteBuffer
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

import static org.codehaus.groovy.control.Phases.CLASS_GENERATION
import static org.codehaus.groovy.control.Phases.SEMANTIC_ANALYSIS

@Grab('com.amazonaws:aws-java-sdk:1.10.27')

class RemoveStaticPrimaryClassNodeOperation extends PrimaryClassNodeOperation {

    @Override
    void call(SourceUnit source44, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
        classNode.visitContents(new GroovyClassVisitor() {
            @Override
            void visitClass(ClassNode node) {
            }

            @Override
            void visitConstructor(ConstructorNode node) {
            }

            @Override
            void visitMethod(MethodNode node) {
                if (node.name == '<clinit>') {
                    node.setCode(new EmptyStatement())
                }
            }

            @Override
            void visitField(FieldNode node) {
            }

            @Override
            void visitProperty(PropertyNode node) {
            }
        })
    }
}

List<GroovyClass> compile(String prefix, File file) {
    CompilationUnit unit = new CompilationUnit()
    unit.addPhaseOperation(new RemoveStaticPrimaryClassNodeOperation(), SEMANTIC_ANALYSIS)
    unit.addSource(SourceUnit.create(prefix, file.text))
    unit.compile(CLASS_GENERATION)
    return unit.getClasses()
}

List<File> getGroovyLibs(List neededJars) {
    if (System.getenv('GROOVY_HOME')) {
        def libs = new File(System.getenv('GROOVY_HOME'), 'lib')
        def groovylibs = libs.listFiles().findAll{jar ->
            neededJars.any{needed -> jar.name =~ needed  }
        }
        if (groovylibs) {
            return groovylibs
        } else {
            println "Cann't find Groovy lib in ${libs.absolutePath}, specify it manually as Grab dependency"
            System.exit(1)
        }
    } else {
        println "Cann't find GROOVY_HOME"
        System.exit(1)
    }
}

List dependencies(File source) {
    final GroovyClassLoader classLoader = new GroovyClassLoader()
    classLoader.parseClass(source.text)
    def files = Grape.resolve([:], Grape.listDependencies(classLoader)).collect{ new JarFile(it.path) }
    files.addAll(getGroovyLibs([/groovy-\d+.\d+.\d+.jar/]).collect{ new JarFile(it) })
    return files
}

byte[] createJar(String prefix, List jars, List<GroovyClass> compiled) {
    ByteArrayOutputStream output = new ByteArrayOutputStream()
    JarOutputStream jos = new JarOutputStream(output)

    jos.putNextEntry(new JarEntry('META-INF/'))

    def manifestEntry = new JarEntry('META-INF/MANIFEST.MF')
    byte[] manifest = "Manifest-Version: 1.0\nMain-Class: ${prefix}\n".getBytes()
    manifestEntry.setSize(manifest.length)
    jos.putNextEntry(manifestEntry)
    jos.write(manifest)

    compiled.each {
        JarEntry main = new JarEntry("${it.name}.class")
        main.setSize(it.bytes.length)
        jos.putNextEntry(main)
        jos.write(it.bytes)
    }

    def directories = ['META-INF/', 'META-INF/MANIFEST.MF']

    jars.each {file ->
        println "Merging ${file.name}"
        file.entries().each { JarEntry entry ->
            if (!directories.contains(entry.name)) {
                byte[] arr = file.getInputStream(entry).getBytes()
                jos.putNextEntry(entry)
                jos.write(arr)
                directories << entry.name
            }
        }
    }

    jos.close()
    return output.toByteArray()
}

byte[] createUberjar(File file, String prefix) {
    List<GroovyClass> compiled = compile(prefix, file)
    def jars = dependencies(file)
    return createJar(prefix, jars, compiled)
}

def cli = new CliBuilder(usage: "script to work with executable jars")
cli.setStopAtNonOption(false)
cli.access(args:1, 'AWS access key')
cli.secret(args:1, 'AWS secret key')
cli.function(args:1, 'AWS function name')
cli.region(args:1, 'AWS region')
cli.handler(args:1, 'AWS handler')
cli.payload(args:1, 'AWS payload for running function')
cli.role(args:1, 'AWS role')
cli.bucket(args:1, 'AWS S3 bucket')
options = cli.parse(args)
if (!options) {
    System.exit(1)
}

File file = new File(args[1])
String prefix = file.name.substring(0, file.name.indexOf('.'))

def function = options.function ?: prefix

if (args[0] == 'write') {
    new File(args[2]).withOutputStream {
        it << createUberjar(file, prefix)
    }
} else if (args[0] == 'upload') {
    byte[] uberjar = createUberjar(file, prefix)

    BasicAWSCredentials credentials = new BasicAWSCredentials(options.access, options.secret)
    Region region = Region.getRegion(Regions.valueOf(options.region))

    AWSLambdaClient client = new AWSLambdaClient(credentials).withRegion(region)
    if (client.listFunctions().functions.any{ it.functionName == function } ) {
        client.updateFunctionCode(
                new UpdateFunctionCodeRequest(zipFile: ByteBuffer.wrap(uberjar), functionName: function))
        client.updateFunctionConfiguration(new UpdateFunctionConfigurationRequest(functionName: function,
                handler: options.handler))
    } else {
        client.createFunction(new CreateFunctionRequest(functionName: function, handler: options.handler,
                runtime: Runtime.Jvm, role: options.role, code: new FunctionCode(zipFile: ByteBuffer.wrap(uberjar))))
    }

    AmazonS3Client s3Client = new AmazonS3Client(credentials).withRegion(region)
    File root = new File("s3")
    root.eachFileRecurse {
        if (it.isFile()) {
            String remoteName = it.absolutePath.substring(root.absolutePath.length() + 1)
            println "Uploading ${it.absolutePath}"
            s3Client.putObject(options.bucket, remoteName, it)
        }
    }
} else if (args[0] == 'test') {
    AWSLambdaClient client = new AWSLambdaClient(new BasicAWSCredentials(options.access, options.secret))
            .withRegion(Region.getRegion(Regions.valueOf(options.region)))
    InvokeResult invokeResult = client.invoke(new InvokeRequest(functionName: function, payload: options.payload))
    println new String(invokeResult.payload.array())
} else {
    println "Command not found"
    System.exit(1)
}
