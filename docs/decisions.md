# Architecture Decision Records

Each record follows the format: **Context → Decision → Consequences**.

---

## ADR-001 — DynamoDB over RDS for the visitor counter

**Context**
We need persistent storage for a single incrementing integer. The access pattern is one read (GET) and one atomic increment (POST) against the same item.

**Decision**
Use DynamoDB with a single-record design (`pk = "VISITOR_COUNTER"`) and the `ADD` update expression.

**Consequences**
- The `ADD` expression is a server-side atomic operation — no read-modify-write loop, no application-level locking, no lost updates under concurrent requests.
- No VPC, no subnet groups, no instance to size or patch. DynamoDB `PAY_PER_REQUEST` charges per operation; cost for this workload is fractions of a cent per day.
- We give up complex queries, joins, and transactions across multiple tables. Those capabilities are not needed here and would only add cost and latency.
- If the access pattern ever grows (e.g., per-user counters, historical time-series), a new GSI or a separate table can be added without changing the existing item structure.

---

## ADR-002 — SQS for async decoupling between the API and notification logic

**Context**
After recording a visit, we want to send an SNS notification for milestone visitors. This side-effect is slower and more failure-prone than the DynamoDB write. Doing it synchronously inside the API Lambda would increase P99 latency and cause HTTP 500s if SNS is temporarily unavailable.

**Decision**
`VisitorCounterFunction` writes a visit event to SQS and returns immediately. `SQSProcessorFunction` handles the notification independently.

**Consequences**
- The API response time is bounded by the DynamoDB write and the SQS `SendMessage` call — both are sub-10ms under normal conditions.
- SQS failures in `VisitorService.enqueueVisitEvent()` are non-fatal: they are logged as warnings and do not affect the HTTP response. The counter has already been incremented at that point.
- SQS provides at-least-once delivery with configurable retry (currently 3 attempts before DLQ). Processing failures do not lose events — they land in the DLQ for inspection and replay.
- The two Lambda functions are independently deployable and scalable. A bug in notification logic cannot crash the API.
- Trade-off accepted: milestone notifications may arrive seconds after the 100th visit rather than instantly.

---

## ADR-003 — SNS for milestone notifications over direct SES calls

**Context**
`SQSProcessorFunction` needs to send an email when a visitor milestone is reached. We could call the SES API directly, or use SNS with an email subscription.

**Decision**
Use SNS with an email subscription protocol.

**Consequences**
- SNS fan-out means adding a second notification channel (SMS, another email, a Lambda, a second SQS queue) requires only a new subscription — zero code change.
- SNS manages delivery retry and per-subscriber state independently. If one subscriber is slow, others are not affected.
- Trade-off: SNS email formatting is plain text and the sender address is `no-reply@sns.amazonaws.com`. Direct SES would give us custom sender domains and HTML templates. This trade-off is acceptable for a demo; a production app with branding requirements should move to SES.

---

## ADR-004 — CloudFront + private S3 over S3 website hosting

**Context**
We need to serve the static frontend over HTTPS globally. S3 has a built-in website hosting mode that serves files over HTTP from a public bucket.

**Decision**
Keep the S3 bucket private, serve all traffic through CloudFront with `redirect-to-https`, and use Origin Access Control (OAC) to allow CloudFront to read from S3.

**Consequences**
- Users always get HTTPS — S3 website hosting is HTTP-only and cannot terminate TLS.
- CloudFront edge caches serve assets from the node closest to the visitor, reducing latency for users far from the S3 region.
- The `aws:SourceArn` condition in the bucket policy scopes S3 read access to exactly this CloudFront distribution. Direct S3 URLs return 403, so there is no way to bypass HTTPS or the CDN layer.
- CloudFront custom error responses (403 → 200 `/index.html`) enable future client-side routing without any server changes.
- Trade-off: CloudFront adds a small per-request cost and a propagation delay (~5 min) for cache invalidations on frontend deploys. Both are negligible at this scale.

---

## ADR-005 — OAC over the legacy Origin Access Identity (OAI)

**Context**
CloudFront needs a credential mechanism to read from a private S3 bucket. AWS has two options: the legacy Origin Access Identity (OAI) and the newer Origin Access Control (OAC).

**Decision**
Use OAC with `SigningProtocol: sigv4`.

**Consequences**
- OAC supports SSE-KMS encrypted S3 buckets. OAI cannot sign requests with KMS keys, so switching to customer-managed encryption would require migrating away from OAI anyway.
- OAC works in all S3 regions including opt-in regions. OAI has region-specific limitations.
- AWS has frozen feature development on OAI and recommends migrating to OAC. Using OAC avoids a future migration.
- No meaningful downside — OAC configuration is marginally more verbose (requires a separate `AWS::CloudFront::OriginAccessControl` resource and an empty `OriginAccessIdentity: ""` placeholder).

---

## ADR-006 — Java 17 runtime

**Context**
Lambda supports multiple JVM runtimes (Java 8, 11, 17, 21). We need to choose one. The local toolchain has Corretto 17 installed via sdkman (`sdk list java` to confirm); Java 21 is not installed.

**Decision**
Java 17 (the current LTS release available in the local toolchain).

**Consequences**
- Java 17 includes text blocks, records, sealed classes, pattern matching for `instanceof`, and switch expressions — all available for use if the codebase grows.
- Java 17 LTS is supported by AWS Lambda and receives long-term security patches. The `Runtime: java17` setting in `template.yaml` and `<java.version>17</java.version>` in `pom.xml` must stay in sync; mismatching them causes a deployment error.
- Cold starts on Java 17 add approximately 3–6 seconds on the first invocation after a period of inactivity (JVM initialisation + static SDK client construction). Subsequent warm invocations are 50–200 ms. This trade-off is acceptable for a personal demo app with occasional traffic.
- Do not upgrade to Java 21 without first running `sdk install java 21.0.x-amzn` and updating both `pom.xml` (`<java.version>21</java.version>`) and `template.yaml` (`Runtime: java21`). Upgrading only one causes a runtime mismatch where the JAR bytecode version does not match the Lambda execution environment.

---

## ADR-007 — Static `final` SDK clients in Lambda handlers

**Context**
AWS SDK v2 clients (`DynamoDbClient`, `SqsClient`, `SnsClient`) are expensive to construct — they perform service endpoint discovery and establish HTTP connection pools. Lambda re-uses the same JVM container for multiple invocations.

**Decision**
Declare SDK clients as `private static final` fields on each handler class, initialised once at class load time.

**Consequences**
- Clients are constructed once per container, not once per invocation. For a container that handles 100 requests, this avoids 99 redundant SDK initialisation cycles.
- The underlying HTTP connection pool stays warm between invocations, reducing per-request latency.
- The production constructor (`VisitorCounterHandler()`) uses these static clients. The test constructor (`VisitorCounterHandler(VisitorService)`) injects a mock service, so the static clients are never instantiated during unit tests.
- Consequence to be aware of: static state is shared across all invocations on the same container. SDK clients are thread-safe and stateless so this is safe here, but mutable static state (e.g., a static counter) would cause subtle bugs under concurrent invocations.

---

## ADR-008 — `UrlConnectionHttpClient` over Apache HttpClient

**Context**
AWS SDK v2 uses Apache HttpClient by default, which adds ~3–5 MB to the JAR and requires its own thread pool.

**Decision**
Replace Apache HttpClient with `UrlConnectionHttpClient` by including `software.amazon.awssdk:url-connection-client` and building with `UrlConnectionHttpClient.builder()`.

**Consequences**
- The uber-JAR is significantly smaller (saves ~3 MB), which reduces S3 upload time, Lambda artifact size, and cold-start class-loading time.
- `UrlConnectionHttpClient` uses the JDK's built-in `HttpURLConnection` — no extra dependency, no background threads.
- Trade-off: `UrlConnectionHttpClient` is synchronous and single-threaded per connection. For Lambda functions that make one AWS API call at a time (as both functions here do), this is not a problem. A function making many parallel SDK calls would benefit from the async `NettyNioAsyncHttpClient`, but that adds even more JAR weight than Apache.

---

## ADR-009 — `ReportBatchItemFailures` for the SQS event source mapping

**Context**
Lambda's SQS event source mapping delivers messages in batches. By default, if a Lambda invocation throws an exception, the entire batch is considered failed and all messages become visible again after their `VisibilityTimeout`.

**Decision**
Enable `FunctionResponseTypes: [ReportBatchItemFailures]` and return `SQSBatchResponse` with the IDs of only the messages that actually failed.

**Consequences**
- Successfully processed messages in a batch are deleted from the queue immediately, even if other messages in the same batch failed. Without this, a single bad message would cause all 9 other messages to be retried.
- Prevents duplicate notifications: if 9 out of 10 messages were processed (including a milestone notification), only the 1 failed message is retried. The 9 successful ones are not re-delivered.
- This requires the handler to never silently swallow exceptions. If `processMessage()` catches an exception internally and returns without re-throwing it, SQS considers the message successfully processed even though it was not. The current code re-throws all processing exceptions to the batch loop.
- The DLQ `maxReceiveCount: 3` still applies — a message that fails 3 times is moved to the DLQ regardless.

---

## ADR-010 — Single-record DynamoDB design for the counter


**Context**
The counter is a single global integer. We could model it as one item per visitor event (append-only log) or as a single aggregated counter item.

**Decision**
Single item with `pk = "VISITOR_COUNTER"` and an atomic `ADD` increment.

**Consequences**
- Reads are a single `GetItem` by primary key — O(1), no scan, no aggregation.
- Writes are a single `UpdateItem ADD` — atomic at the DynamoDB level, no application-level concurrency control needed.
- All reads and writes hit the same partition key, which concentrates traffic on a single DynamoDB partition. At the expected traffic levels for a demo app this is fine. If this pattern were used for a high-throughput counter (millions of writes per second), DynamoDB hot-partition throttling would become a concern — the solution would be sharded counters or DynamoDB's `ATOMIC_COUNTER_INCREMENT` with partition spreading.
- We lose per-visitor history. If future requirements include "show a graph of visitors over time", a second table (or a DynamoDB Stream feeding a time-series store) would be the right addition rather than changing this item.

---

## ADR-011 — `AWS::ApiGateway::Account` for CloudWatch Logs access

**Context**
The `VisitorApi` stage is configured with `AccessLogSetting.DestinationArn` pointing to a CloudWatch Logs log group. API Gateway requires an IAM role ARN to be registered at the account level (not the API level) before it can write to CloudWatch Logs. Without this setting, the API Gateway stage fails to create with: _"CloudWatch Logs role ARN must be set in account settings to enable logging"_.

**Decision**
Add two resources to `template.yaml`:
1. `ApiGatewayCloudWatchRole` — an `AWS::IAM::Role` assumed by `apigateway.amazonaws.com` with the `AmazonAPIGatewayPushToCloudWatchLogs` managed policy attached.
2. `ApiGatewayAccount` — an `AWS::ApiGateway::Account` resource that sets `CloudWatchRoleArn` to the role above. `VisitorApi` has `DependsOn: ApiGatewayAccount` to enforce creation order.

**Consequences**
- The CloudWatch Logs role ARN is set once at the account level and applies to all API Gateway APIs in the account and region. It is not per-API.
- This is idempotent on stack updates — re-deploying the stack simply sets the account setting to the same role ARN again.
- **Multi-stack caution:** if another CloudFormation stack in the same account and region also manages an `AWS::ApiGateway::Account` resource, the two stacks will fight over the account-level setting. In that case, remove `ApiGatewayCloudWatchRole` and `ApiGatewayAccount` from this stack and rely on the other stack's setting. Alternatively, manage it once in a shared infrastructure stack.
- The alternative (setting the role manually via `aws apigateway update-account`) was rejected because it is a manual step that would be lost if the account were recreated or if another operator set up a fresh environment.
