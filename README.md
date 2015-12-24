# groovylambda

This is minimalistic framework for Amazon Lambda written in Groovy. It helps with testing things locally
and with uploading to AWS.

There are two scripts and example application. Example application is singe page application with HTML and JavaScript on S3,
and server-side API in Lambda. JavaScript calls lambda function and it returns data from Amazon and renders it for user.

To try it out, checkout this project. Client code is located in s3 folder. It is just HTML and JavaScript, no
parsing, no magic. There is only one convention - endpoints are in config.js file, but this is only to replace it for
local testing and can be adjusted as needed.
Server side code is in lambda/Api.groovy. Router is AWS lambda handler method. In this example there is
controller, but from router it is up to you how to build your application. Everything it returns will be serialized as
JSON.

To test things locally - run mock.groovy script. It creates local HTTP server, mocks config.js, injects configuration,
executes server side files.

To upload code to lambda and s3, run lambda.groovy. It packages lambda into jar and uploads to AWS. Also it uploads s3
folder to specified bucket.
