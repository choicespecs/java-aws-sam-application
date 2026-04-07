# Architecture

## System diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  CLIENT                                                             │
│  Browser                                                            │
└───────────┬─────────────────────────┬───────────────────────────────┘
            │ HTTPS (static assets)   │ HTTPS (API calls)
            ▼                         ▼
┌───────────────────┐     ┌───────────────────────┐
│   CloudFront      │     │    API Gateway         │
│   Distribution    │     │    REST API /visit     │
│                   │     │    (throttle + CORS)   │
│  Edge cache       │     └───────────┬────────────┘
│  HTTPS terminate  │                 │ Lambda proxy
│  SPA 403→200      │                 ▼
└────────┬──────────┘     ┌───────────────────────┐
         │ OAC / SigV4    │  VisitorCounterFunction│
         ▼                │  Java 17 Lambda        │
┌────────────────┐        │                        │
│   S3 Bucket    │        │  GET  → read counter   │
│   (private)    │        │  POST → increment +    │
│                │        │         enqueue event  │
│  index.html    │        └────┬──────────┬─────────┘
│  app.js        │             │          │
│  config.js*    │             │ GetItem  │ UpdateItem (ADD)
└────────────────┘             │ /        │ + SendMessage
                               ▼          ▼
                    ┌──────────────┐  ┌────────────────────┐
                    │  DynamoDB    │  │   SQS Queue        │
                    │  Table       │  │                    │
                    │              │  │  VisibilityTimeout │
                    │  pk (PK)     │  │  180s              │
                    │  count (N)   │  │  maxReceive: 3     │
                    │  lastUpdated │  └────────┬───────────┘
                    └──────────────┘           │ failed → DLQ
                                               │ (after 3 attempts)
                                               ▼
                                    ┌───────────────────────┐
                                    │  SQSProcessorFunction │
                                    │  Java 17 Lambda       │
                                    │                       │
                                    │  Batch up to 10 msgs  │
                                    │  ReportBatchItem      │
                                    │  Failures             │
                                    │                       │
                                    │  every 100th visitor  │
                                    │  → Publish to SNS     │
                                    └──────────┬────────────┘
                                               │
                                               ▼
                                    ┌─────────────────────┐
                                    │   SNS Topic         │
                                    │                     │
                                    │   email subscriber  │──► Inbox
                                    └─────────────────────┘

* config.js is generated at deploy time and gitignored
```

## Components

### S3 (static hosting)

Stores the three frontend files: `index.html`, `app.js`, and `config.js`. The bucket has all public access blocked; only CloudFront can read objects, enforced by the bucket policy's `aws:SourceArn` condition scoped to this specific distribution.

Versioning is enabled so accidental overwrites of frontend files are recoverable.

### CloudFront (CDN + HTTPS)

Terminates TLS and serves files from the edge node nearest the visitor. The `redirect-to-https` viewer protocol policy means no plaintext requests ever reach S3.

The `CachingOptimized` managed cache policy caches on `Accept-Encoding` and compresses responses at the edge. Static files like `app.js` benefit from this without any manual configuration.

Custom error responses rewrite `403` and `404` from S3 to `200 /index.html`, enabling client-side routing for any future SPA expansion.

### API Gateway (REST API)

Provides a stable HTTPS endpoint (`/visit`) that decouples the frontend URL from the Lambda ARN. Configured with:

- **CORS** — required because the frontend origin (`*.cloudfront.net`) differs from the API origin (`execute-api.amazonaws.com`).
- **Throttling** — 50 RPS steady-state, 100 burst — protects DynamoDB and Lambda from traffic spikes.
- **Access logging** — per-request logs (IP, method, status, latency) written to CloudWatch Logs for debugging and cost analysis.

### VisitorCounterFunction (Java 17 Lambda)

Handles synchronous API requests. Reads the counter on `GET`, atomically increments it on `POST`, then enqueues a visit event to SQS. The SQS write is non-fatal — if it fails, the counter has already been incremented and the HTTP response is still returned successfully.

AWS SDK clients (`DynamoDbClient`, `SqsClient`) are `static final` fields so they survive Lambda container reuse and their TCP connection pools stay warm between requests.

### DynamoDB (counter storage)

Holds a single item:

```
pk           = "VISITOR_COUNTER"   String (partition key)
count        = 42                  Number (atomic counter)
lastUpdated  = "2024-..."          String (ISO-8601)
```

The `UpdateItem` expression `ADD #count :1` is an atomic server-side increment — no read-modify-write cycle is needed and there are no race conditions under concurrent requests.

`PAY_PER_REQUEST` billing means the table scales from zero traffic to any spike without capacity planning. `DeletionPolicy: Retain` in the SAM template ensures the table survives stack teardown.

### SQS + DLQ (async decoupling)

The main queue buffers visit events between `VisitorCounterFunction` (producer) and `SQSProcessorFunction` (consumer). The two Lambda functions are completely independent: the API response is not affected by downstream processing speed or failures.

The dead-letter queue captures messages that fail processing three times. A CloudWatch alarm on `ApproximateNumberOfMessagesVisible` in the DLQ is the recommended production addition to alert on persistent failures.

### SQSProcessorFunction (Java 17 Lambda)

Triggered by the SQS event source mapping, which handles polling and batching automatically. Processes up to 10 messages per invocation. Returns `SQSBatchResponse` with the IDs of any messages that failed, so only those are retried — successfully processed messages in the same batch are not re-delivered.

Sends an SNS notification when `visitorCount % 100 == 0`.

### SNS (notifications)

Fan-out topic with an email subscription. SNS handles delivery retry and tracks per-subscriber state independently. Adding a second notification channel (SMS, Lambda, SQS) requires only a new subscription — no code changes.

### IAM roles

Two roles, one per Lambda function, each with the minimum permissions to do its job:

| Role | DynamoDB | SQS | SNS |
|------|----------|-----|-----|
| VisitorCounterRole | GetItem, UpdateItem, PutItem | SendMessage | — |
| SQSProcessorRole | — | ReceiveMessage, DeleteMessage, GetQueueAttributes, ChangeMessageVisibility | Publish |

Both roles use `AWSLambdaBasicExecutionRole` (the AWS managed policy) for CloudWatch Logs write access.

A third role, `ApiGatewayCloudWatchRole`, is assumed by the `apigateway.amazonaws.com` service principal and grants the `AmazonAPIGatewayPushToCloudWatchLogs` managed policy. This is an account-level setting (managed by the `AWS::ApiGateway::Account` resource) that allows API Gateway to write access logs to CloudWatch Logs. Without it, any `AccessLogSetting` on an API stage fails at deploy time.

## Data model

### DynamoDB — `${stackName}-visitors`

| Attribute | Type | Description |
|-----------|------|-------------|
| `pk` | String (PK) | Always `"VISITOR_COUNTER"` — single-record design |
| `count` | Number | Monotonically increasing visitor count |
| `lastUpdated` | String | ISO-8601 timestamp of the last increment |

### SQS message body (JSON)

```json
{
  "visitorId":    "203.0.113.42",
  "visitorCount": 100,
  "timestamp":    "2024-01-15T10:30:00Z"
}
```

`visitorId` is the source IP from the API Gateway request context, or `"anon-<millis>"` when unavailable (e.g., local testing).

## Environment variables (Lambda)

All values are injected by CloudFormation via the `Globals.Function.Environment` block in `template.yaml`. No values are hardcoded in source.

| Variable | Set from | Used by |
|----------|----------|---------|
| `DYNAMODB_TABLE` | `!Ref VisitorTable` | VisitorService |
| `SQS_QUEUE_URL` | `!Ref VisitorQueue` | VisitorService |
| `SNS_TOPIC_ARN` | `!Ref NotificationTopic` | NotificationService |
| `ENVIRONMENT` | `!Ref Environment` parameter | Logging context |

## CloudWatch Logs

Each Lambda function and the API Gateway stage write to dedicated log groups with 14-day retention:

```
/aws/lambda/serverless-visitor-app-visitor-counter
/aws/lambda/serverless-visitor-app-sqs-processor
/aws/apigateway/serverless-visitor-app
```
