@Grab('com.amazonaws:aws-java-sdk-dynamodb:1.10.27')
@Grab('com.amazonaws:aws-lambda-java-core:1.1.0')
@Grab('com.amazonaws:aws-lambda-java-log4j:1.0.0')

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBAttribute
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBHashKey
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBTable

@DynamoDBTable(tableName = "test")
class TestDomain {
    @DynamoDBHashKey
    String id

    @DynamoDBAttribute(attributeName = "intAttribute")
    Integer val
}

class TestController {
    Map read(Map params, DynamoDBMapper mapper) {
        Test obj = mapper.load(TestDomain.class, "1")
        if (!obj) {
            [result:0]
        } else {
            [result:obj.val]
        }
    }

    Map add(Map params, DynamoDBMapper mapper) {
        Test obj = mapper.load(TestDomain.class, "1")
        if (!obj) {
            obj = new Test(id:"1", val:0)
        }
        obj.val++
        mapper.save(obj)
    }
}

public Map router(Map data) throws IOException {
    BasicAWSCredentials credentials = new BasicAWSCredentials(data.access, data.secret)
    Region region = Region.getRegion(Regions.valueOf(data.region))
    AmazonDynamoDBClient client = new AmazonDynamoDBClient(credentials).withRegion(region)
    DynamoDBMapper mapper = new DynamoDBMapper(client)

    if (!TestController.methods.name.contains(data.params.function)) {
        throw new RuntimeException("function not found")
    } else {
        return new TestController().invokeMethod(data.params.function, [data.params, mapper])
    }
}
