@Grab(group = 'io.undertow', module = 'undertow-core', version = '1.1.2.Final')
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.undertow.Undertow
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.BlockingHandler
import io.undertow.server.handlers.PathHandler
import io.undertow.server.handlers.resource.FileResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import io.undertow.util.HttpString

import java.lang.reflect.Method

classLoader = new GroovyClassLoader()

void parseLambdaRequest(HttpServerExchange exchange) {
    String methodName = exchange.getQueryParameters()['methodName'][0]
    String className = exchange.getQueryParameters()['className'][0]
    File classFile = new File("lambda/${className}.groovy")
    classLoader.parseClass(classFile.text, className)

    Class api = classLoader.loadClass(className)
    Method method = api.methods.find { it.name == methodName }
    Map params = [:]
    params.params = new JsonSlurper().parseText(exchange.inputStream.text)
    params.access = "xxx"
    params.secret = "yyy"
    params.region = "EU_WEST_1"
    println "Found ${api}.${method} for request ${className}.${methodName} with parameters ${params}"

    def output = method.invoke(api.newInstance(), params)

    exchange.getResponseHeaders().add(new HttpString("Access-Control-Allow-Origin"), "*")
    exchange.getResponseSender().send(JsonOutput.toJson(output))
}

void parseConfig(HttpServerExchange exchange) {
    exchange.getResponseSender()
        .send("var HOST = '/test?className=Api&methodName=router';")
}

Undertow.builder()
    .addHttpListener(8080, null)
    .setHandler(
        new PathHandler(new ResourceHandler(new FileResourceManager(new File("."), 1000)))
        .addPrefixPath("/test", new BlockingHandler(this.&parseLambdaRequest))
        .addPrefixPath("/config.js", this.&parseConfig))
    .build()
    .start()
