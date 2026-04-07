# AWS SDK for Java v2 — Usage Reference

This document explains how the AWS SDK for Java v2 is used in this project: which dependencies are declared, how clients are constructed and configured, what API calls each service layer makes, how requests and responses are modelled, and how errors are handled.

---

## SDK version and dependencies

The project uses **AWS SDK for Java v2**, version `2.25.11`, managed via the SDK BOM (Bill of Materials) in `pom.xml`.

```xml
<!-- BOM — pins all SDK module versions to a consistent release -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>bom</artifactId>
      <version>2.25.11</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

With the BOM imported, individual service modules declare no version — the BOM guarantees they are all from the same compatible release:

```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>dynamodb</artifactId>       <!-- DynamoDbClient -->
</dependency>
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>sqs</artifactId>            <!-- SqsClient -->
</dependency>
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>sns</artifactId>            <!-- SnsClient -->
</dependency>
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>url-connection-client</artifactId>  <!-- HTTP transport -->
</dependency>
```

### Why v2, not v1

SDK v2 (`software.amazon.awssdk`) is a complete rewrite of the legacy v1 (`com.amazonaws`). Key differences relevant to this project:

| Aspect | SDK v1 | SDK v2 (used here) |
|--------|--------|-------------------|
| Package root | `com.amazonaws` | `software.amazon.awssdk` |
| Request model | Mutable POJOs with setters | Immutable builders (`Request.builder().field(x).build()`) |
| HTTP client | Apache HttpClient (default) | Pluggable; this project uses `UrlConnectionHttpClient` |
| Response model | Result objects with getters | Immutable response objects |
| Exception hierarchy | `AmazonServiceException` | `AwsServiceException` → `SdkException` |

> **Note:** The Lambda runtime dependency (`com.amazonaws:aws-lambda-java-core`) still uses the v1 namespace — this is the Lambda invocation contract, not the AWS service SDK. Both coexist without conflict.

---

## HTTP transport: `UrlConnectionHttpClient`

By default, SDK v2 uses the Apache HttpClient, which adds ~3 MB to the JAR and initialises its own thread pool. This project explicitly replaces it with `UrlConnectionHttpClient` on every client:

```java
DynamoDbClient.builder()
    .httpClientBuilder(UrlConnectionHttpClient.builder())
    .build();
```

`UrlConnectionHttpClient` wraps the JDK's built-in `java.net.HttpURLConnection`. It has no external dependencies, adds zero JAR weight beyond the SDK itself, and requires no background threads. The trade-off (synchronous, one connection per call) is irrelevant here because each Lambda function makes at most one AWS API call at a time.

See ADR-008 in `docs/decisions.md` for the full rationale.

---

## Client construction and lifecycle

All three service clients are declared as `private static final` fields on their respective handler classes:

```java
// VisitorCounterHandler.java
private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();

private static final SqsClient SQS = SqsClient.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();

// SQSProcessorHandler.java
private static final SnsClient SNS = SnsClient.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();
```

### Why `static final`

Lambda reuses the same JVM container (and therefore the same class instance) across multiple invocations. `static final` fields are initialised once when the class is first loaded — on the cold start — and then reused for every subsequent warm invocation.

Building an SDK client is expensive: it performs endpoint discovery, resolves credentials, and opens an HTTP connection pool. Doing this on every invocation would add 50–200 ms to each request. With `static final`, the cost is paid once.

### Credential resolution

No credentials are configured explicitly. The SDK's **default credential provider chain** is used, which checks sources in this order:

1. Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
2. System properties (`aws.accessKeyId`, `aws.secretKey`)
3. Web Identity Token (used by Lambda — this is what actually resolves in production)
4. `~/.aws/credentials` profile (used in local development)
5. EC2/ECS instance metadata

In production, Lambda automatically injects short-lived credentials via the Web Identity Token mechanism tied to the function's execution role. The SDK picks these up without any explicit configuration.

### Region resolution

No region is configured explicitly. The SDK resolves it from the `AWS_REGION` environment variable, which Lambda sets automatically from the deployment region.

---

## DynamoDB — `DynamoDbClient`

Used in `VisitorService`. Two operations: read the counter (`GetItem`) and atomically increment it (`UpdateItem`).

### GetItem — reading the current count

```java
GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
        .tableName(tableName)                              // from DYNAMODB_TABLE env var
        .key(Map.of("pk", AttributeValue.fromS(COUNTER_PK)))  // pk = "VISITOR_COUNTER"
        .projectionExpression("#c")                        // only fetch the count attribute
        .expressionAttributeNames(Map.of("#c", "count"))  // alias: count is a reserved word
        .build());
```

**Request breakdown:**

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `tableName` | `serverless-visitor-app-visitors` | Target table, from env var |
| `key` | `{"pk": {"S": "VISITOR_COUNTER"}}` | The single partition key that identifies the counter item |
| `projectionExpression` | `#c` | Fetch only the `count` attribute — avoids reading `lastUpdated` unnecessarily |
| `expressionAttributeNames` | `{"#c": "count"}` | `count` is a DynamoDB reserved word; the alias avoids a parse error |

**Response handling:**

```java
if (response.hasItem() && response.item().containsKey("count")) {
    return Long.parseLong(response.item().get("count").n());
}
return 0L;  // item doesn't exist yet (fresh table)
```

`response.item()` returns a `Map<String, AttributeValue>`. Each `AttributeValue` carries a typed accessor — `.n()` returns the Number attribute as a `String`, which is then parsed to `long`. If the item does not exist (fresh deployment, never incremented), `hasItem()` returns false and the method returns `0`.

### UpdateItem — atomic increment

```java
UpdateItemResponse response = dynamoDb.updateItem(UpdateItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("pk", AttributeValue.fromS(COUNTER_PK)))
        .updateExpression("ADD #count :inc SET #lastUpdated = :ts")
        .expressionAttributeNames(Map.of(
                "#count",       "count",
                "#lastUpdated", "lastUpdated"))
        .expressionAttributeValues(Map.of(
                ":inc", AttributeValue.fromN("1"),
                ":ts",  AttributeValue.fromS(Instant.now().toString())))
        .returnValues(ReturnValue.UPDATED_NEW)
        .build());
```

**Request breakdown:**

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `updateExpression` | `ADD #count :inc SET #lastUpdated = :ts` | Two operations in one request |
| `ADD #count :inc` | Adds `:inc` (1) to `count` | Atomic server-side increment; creates the attribute if absent |
| `SET #lastUpdated = :ts` | Sets timestamp | Records when the last increment happened |
| `expressionAttributeNames` | Aliases for `count` and `lastUpdated` | Both are reserved words in DynamoDB expression syntax |
| `expressionAttributeValues` | `{":inc": 1, ":ts": "<ISO-8601>"}` | Typed values bound into the expression |
| `returnValues` | `UPDATED_NEW` | Returns the attribute values after the update — used to read back the new count |

**Why `ADD` is atomic:**
The `ADD` expression is evaluated entirely server-side by DynamoDB. There is no read-modify-write cycle in the application — the old value is never fetched. DynamoDB guarantees that concurrent `ADD` operations on the same item are serialised correctly, making this safe under any level of concurrency without application-level locking.

**Response handling:**

```java
long newCount = Long.parseLong(response.attributes().get("count").n());
```

`response.attributes()` returns the post-update values requested by `UPDATED_NEW`. The new `count` is read back and returned to the caller without a second `GetItem`.

### DynamoDB exception handling

```java
} catch (DynamoDbException e) {
    log.error("DynamoDB GetItem failed", e);
    throw new RuntimeException("Failed to retrieve visitor count", e);
}
```

`DynamoDbException` is the SDK v2 base class for all DynamoDB service errors. It extends `AwsServiceException` which extends `SdkException`. Common subtypes that may appear:

| Exception | Cause |
|-----------|-------|
| `ResourceNotFoundException` | Table name is wrong or table was deleted |
| `ProvisionedThroughputExceededException` | Throughput limit hit (not applicable — table uses PAY_PER_REQUEST) |
| `InternalServerErrorException` | DynamoDB-side transient error; can be retried |
| `SdkClientException` | Network failure before the request reached DynamoDB |

The SDK automatically retries transient errors (including throttling and 5xx responses) using exponential backoff before throwing. By the time the exception reaches application code, the SDK has already exhausted its retry budget.

---

## SQS — `SqsClient`

Used in `VisitorService`. One operation: enqueue a visit event after a successful DynamoDB increment.

### SendMessage

```java
sqs.sendMessage(SendMessageRequest.builder()
        .queueUrl(queueUrl)       // from SQS_QUEUE_URL env var
        .messageBody(messageBody) // JSON: {visitorId, visitorCount, timestamp}
        .build());
```

**Request breakdown:**

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `queueUrl` | Full HTTPS URL of the SQS queue | Identifies the destination queue |
| `messageBody` | JSON string | The visit event payload consumed by SQSProcessorFunction |

The message body is serialised by Jackson's `ObjectMapper`:

```java
String messageBody = MAPPER.writeValueAsString(Map.of(
        "visitorId",    visitorId,
        "visitorCount", visitorCount,
        "timestamp",    Instant.now().toString()
));
```

No message attributes, delay seconds, or deduplication ID are set — standard queue delivery is sufficient for this use case.

### SQS exception handling — non-fatal

```java
} catch (SqsException e) {
    log.warn("SQS SendMessage failed (non-fatal): {}", e.getMessage());
} catch (Exception e) {
    log.warn("Failed to serialise SQS message (non-fatal)", e);
}
```

SQS failure is deliberately non-fatal. By the time `SendMessage` is called, `UpdateItem` has already committed the counter increment. Failing the HTTP response at this point would tell the user their visit was not recorded when it actually was.

The failure is logged as `WARN` (not `ERROR`) so it is visible in CloudWatch Logs without triggering error-rate alarms. The consequence is that no milestone notification will fire for that specific visit. See ADR-002 and `docs/flow.md` §3.

`SqsException` is the SDK v2 base class for SQS service errors. `SdkClientException` (network failure) is caught by the broader `Exception` catch block.

---

## SNS — `SnsClient`

Used in `NotificationService`. One operation: publish a milestone notification.

### Publish

```java
sns.publish(PublishRequest.builder()
        .topicArn(topicArn)    // from SNS_TOPIC_ARN env var
        .subject(safeSubject)  // truncated to 100 chars if necessary
        .message(message)      // notification body text
        .build());
```

**Request breakdown:**

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `topicArn` | Full ARN of the SNS topic | Identifies the destination topic |
| `subject` | `🎉 Milestone reached: 100 visitors!` | Email subject line (email subscribers only) |
| `message` | Multi-line notification body | Delivered to all subscribers |

**Subject truncation:**

```java
String safeSubject = subject.length() > 100
        ? subject.substring(0, 97) + "..."
        : subject;
```

The SNS email protocol enforces a 100-character subject limit. Longer subjects are silently rejected or truncated by SNS. The application pre-truncates to ensure the message is always delivered as intended.

### SNS exception handling — fatal

```java
} catch (SnsException e) {
    log.error("SNS Publish failed: topicArn={} errorCode={}", topicArn,
              e.awsErrorDetails().errorCode(), e);
    throw new RuntimeException("Failed to publish SNS notification", e);
}
```

Unlike SQS failure in `VisitorService`, SNS failure in `NotificationService` is re-thrown. This causes `SQSProcessorHandler.processMessage()` to catch the exception and add the message ID to `batchItemFailures`. SQS then retries the message up to `maxReceiveCount` (3) times before moving it to the DLQ.

This is intentional: the message contains the visitor count, and a failed SNS publish for a milestone visit means the notification was not sent. Retrying gives SNS a chance to recover from a transient outage.

`e.awsErrorDetails().errorCode()` extracts the AWS error code string (e.g. `"AuthorizationError"`, `"InvalidParameter"`) from the response. This is logged to make IAM and configuration errors easier to diagnose without reading raw stack traces.

---

## Request/response builder pattern

Every SDK v2 request is constructed with an immutable builder. The pattern is consistent across all three services:

```java
// 1. Start the builder for the specific request type
SomeRequest request = SomeRequest.builder()
        .field1(value1)
        .field2(value2)
        .build();              // produces an immutable SomeRequest

// 2. Pass to the client method
SomeResponse response = client.someOperation(request);

// 3. Read from the immutable response
String value = response.someField();
```

**Benefits of this pattern:**
- Compile-time safety — unknown fields cause compilation errors, not runtime exceptions
- Immutability — requests and responses cannot be mutated after construction; safe to log and share across threads
- Discoverability — IDE completion on the builder surfaces all available fields and their types
- No nulls on required fields — the builder throws `NullPointerException` at `.build()` time if a required field is missing, rather than failing at the network call

### `AttributeValue` — DynamoDB type system

DynamoDB is a typed store. The SDK represents each attribute value as an `AttributeValue` with a type discriminator:

```java
AttributeValue.fromS("VISITOR_COUNTER")  // String
AttributeValue.fromN("1")                // Number (always a String in the wire format)
AttributeValue.fromBool(true)            // Boolean
AttributeValue.fromL(List.of(...))       // List
AttributeValue.fromM(Map.of(...))        // Map
```

Reading back:

```java
attributeValue.s()     // String value
attributeValue.n()     // Number value (as String — parse with Long.parseLong etc.)
attributeValue.bool()  // Boolean value
```

This project uses only `S` (partition key) and `N` (counter) types.

---

## SDK v2 exception hierarchy

All SDK exceptions share a common hierarchy:

```
Throwable
└── RuntimeException
    └── SdkException                        (software.amazon.awssdk.core.exception)
        ├── SdkClientException              — failure before reaching AWS (network, config)
        └── AwsServiceException             — AWS returned an error response
            ├── DynamoDbException           — any DynamoDB error
            │   ├── ResourceNotFoundException
            │   ├── ConditionalCheckFailedException
            │   └── ... (other DynamoDB subtypes)
            ├── SqsException                — any SQS error
            └── SnsException                — any SNS error
```

All SDK exceptions are **unchecked** (extend `RuntimeException`). They do not need to be declared in `throws` clauses.

`AwsServiceException` provides:
- `.statusCode()` — HTTP status code of the AWS response
- `.awsErrorDetails().errorCode()` — AWS error code string (e.g. `"AccessDeniedException"`)
- `.awsErrorDetails().errorMessage()` — human-readable description
- `.requestId()` — unique request ID for AWS support

`SdkClientException` indicates the request never reached AWS — typically a DNS failure, connection timeout, or credential resolution error.

---

## How the SDK connects to each AWS service

At runtime inside a Lambda function, the call path for each service is:

```
Application code
      │
      │  client.operation(request)
      ▼
SDK request marshaller
      │  serialises request to HTTP (JSON or query-string depending on service)
      ▼
UrlConnectionHttpClient
      │  HTTPS POST to service endpoint
      │  e.g. https://dynamodb.us-east-1.amazonaws.com
      │       https://sqs.us-east-1.amazonaws.com
      │       https://sns.us-east-1.amazonaws.com
      ▼
AWS service endpoint (regional)
      │  authenticates using SigV4 signature on the request
      │  processes the operation
      ▼
HTTP response (JSON)
      ▼
SDK response unmarshaller
      │  deserialises JSON to immutable response object
      ▼
Application code receives response or exception
```

### SigV4 request signing

The SDK automatically signs every request with **AWS Signature Version 4** before sending. Signing uses the resolved credentials (from the Lambda execution role in production) and the resolved region. The signature is attached as an `Authorization` header on the HTTPS request. AWS validates the signature server-side and rejects requests with invalid or expired signatures.

This happens transparently — application code never handles signing directly.

### Service endpoints

The SDK resolves the service endpoint from the region. In production (Lambda in `us-east-1`):

| Client | Default endpoint |
|--------|-----------------|
| `DynamoDbClient` | `https://dynamodb.us-east-1.amazonaws.com` |
| `SqsClient` | `https://sqs.us-east-1.amazonaws.com` |
| `SnsClient` | `https://sns.us-east-1.amazonaws.com` |

No custom endpoints are configured. For local development with `sam local invoke`, the Lambda container runs locally but the clients still call the real AWS endpoints, so real AWS resources (and credentials) are required.

---

## SDK usage in tests

The unit tests mock at the **service layer** boundary, not at the SDK client level:

```java
@Mock private VisitorService visitorService;      // in VisitorCounterHandlerTest
@Mock private NotificationService notificationService;  // in SQSProcessorHandlerTest
```

The test constructors on each handler accept injected services:

```java
// VisitorCounterHandler.java
public VisitorCounterHandler(VisitorService visitorService) {
    this.visitorService = visitorService;
}

// SQSProcessorHandler.java
public SQSProcessorHandler(NotificationService notificationService) {
    this.notificationService = notificationService;
}
```

This means **no SDK client is ever instantiated in tests** — the `static final` clients are only constructed when the no-arg production constructor is called. Tests exercise the full handler logic (routing, CORS headers, error responses, batch failure reporting) without needing real AWS credentials or a network connection.

`VisitorService` and `NotificationService` each have a full constructor that accepts a client and explicit resource identifiers (table name, queue URL, topic ARN), enabling lower-level integration tests against real or localstack resources if ever needed:

```java
// VisitorService — full constructor for integration testing
public VisitorService(DynamoDbClient dynamoDb, SqsClient sqs,
                      String tableName, String queueUrl) { ... }

// NotificationService — full constructor for integration testing
public NotificationService(SnsClient sns, String topicArn) { ... }
```
