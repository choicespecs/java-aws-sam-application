# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A serverless visitor-counter web app deployed on AWS via SAM:
- **Static frontend** (S3 + CloudFront) — `frontend/`
- **REST API** (API Gateway) → **Java 17 Lambda** handlers
- **DynamoDB** atomic counter (single-record design, `pk = "VISITOR_COUNTER"`)
- **SQS queue** (+ DLQ) for async visit event processing
- **SNS topic** for milestone email notifications (every 100th visitor)

For deeper context see:
- [`docs/architecture.md`](docs/architecture.md) — component diagram, data model, IAM boundaries
- [`docs/decisions.md`](docs/decisions.md) — ADRs covering every major technology choice
- [`docs/flow.md`](docs/flow.md) — step-by-step request and data flows including error paths
- [`docs/console-guide.md`](docs/console-guide.md) — AWS Console monitoring reference
- [`DEPLOYMENT.md`](DEPLOYMENT.md) — full deploy/teardown/redeploy workflow

---

## Key commands

```bash
# Build Lambda artifact (must run before deploy or local invoke)
sam build

# Run all unit tests (no Docker, no AWS needed — all AWS calls are mocked)
mvn test

# Run one test class / method
mvn test -Dtest=VisitorCounterHandlerTest
mvn test -Dtest=VisitorCounterHandlerTest#get_returnsCurrentCount

# Validate SAM template
sam validate --lint

# Local Lambda invocation (Docker + deployed stack required; hits real DynamoDB/SQS/SNS)
sam local invoke VisitorCounterFunction --event events/api-get.json  --env-vars local-env.json
sam local invoke VisitorCounterFunction --event events/api-post.json --env-vars local-env.json
sam local invoke SQSProcessorFunction   --event events/sqs-event.json --env-vars local-env.json

# Local API Gateway (port 3000)
sam local start-api --env-vars local-env.json

# Deploy (first time — guided)
sam deploy --guided --parameter-overrides NotificationEmail=you@example.com

# Deploy (subsequent)
sam build && sam deploy
```

---

## Architecture

```
Browser → CloudFront → S3 (frontend/)
Browser → API Gateway /visit (GET|POST)
               ↓
       VisitorCounterFunction (Java 17)
         ├── GET  → DynamoDB GetItem
         └── POST → DynamoDB UpdateItem (atomic ADD)
                         └── SQS SendMessage (non-fatal if fails)
                                   ↓
                        SQSProcessorFunction (Java 17)
                          └── every 100th visitor → SNS Publish → email
```

---

## Code layout

```
src/main/java/com/example/
  handlers/
    VisitorCounterHandler.java   API Gateway RequestHandler (GET + POST + OPTIONS /visit)
    SQSProcessorHandler.java     SQS RequestHandler, returns SQSBatchResponse
  service/
    VisitorService.java          DynamoDB counter + SQS enqueue
    NotificationService.java     SNS publish wrapper

src/test/java/com/example/handlers/
  VisitorCounterHandlerTest.java
  SQSProcessorHandlerTest.java

frontend/
  index.html    Single-page UI
  app.js        Fetch calls to API; reads window.API_BASE_URL from config.js
  config.js     Generated at deploy time — NOT committed (contains live API URL)

events/         Sample payloads for sam local invoke
docs/           Architecture, decision records, flow diagrams, console guide
template.yaml   All AWS resources (S3, CloudFront, API GW, Lambda, DDB, SQS, SNS, IAM)
samconfig.toml  SAM CLI deploy config (prod + dev environments)
pom.xml         Maven — produces uber-JAR via maven-shade-plugin
```

---

## Data model

### DynamoDB — `serverless-visitor-app-visitors`

Single-record design. The table always has exactly one item after the first POST:

| Attribute | Type | Value |
|-----------|------|-------|
| `pk` | String (partition key) | `"VISITOR_COUNTER"` — hardcoded, never changes |
| `count` | Number | Monotonically increasing; incremented by `UpdateItem ADD` |
| `lastUpdated` | String | ISO-8601 timestamp of the last increment |

The `ADD` expression creates both the item and the attribute if they don't exist yet, so there is no initialisation step needed on a fresh table.

### SQS message body (JSON)

Produced by `VisitorService.enqueueVisitEvent()`, consumed by `SQSProcessorHandler`:

```json
{
  "visitorId":    "203.0.113.42",
  "visitorCount": 42,
  "timestamp":    "2024-01-15T10:30:00.123Z"
}
```

`visitorId` is the source IP from the API Gateway request context (`request.getRequestContext().getIdentity().getSourceIp()`), or `"anon-<millis>"` when unavailable (e.g. local testing without a request context).

`SQSProcessorHandler.processMessage()` validates that all three fields are present and non-null; missing fields throw `IllegalArgumentException`, which marks the message as a batch item failure.

### Milestone notification format

`SQSProcessorHandler` publishes to SNS when `visitorCount % 100 == 0`:

```
Subject : 🎉 Milestone reached: 100 visitors!
Body    : Your serverless website has reached 100 total visitors!

          Latest visitor : 203.0.113.42
          Recorded at    : 2024-01-15T10:30:00Z

          Next milestone : 200 visitors
```

Subject is truncated to 100 characters if necessary (SNS email protocol limit, handled in `NotificationService.sendNotification()`).

---

## Lambda environment variables

All values are injected by CloudFormation via `Globals.Function.Environment` in `template.yaml`. Both functions receive all three AWS resource variables.

| Variable | Resolved from | Used by |
|----------|--------------|---------|
| `DYNAMODB_TABLE` | `!Ref VisitorTable` → table name | `VisitorService` |
| `SQS_QUEUE_URL` | `!Ref VisitorQueue` → queue URL | `VisitorService` |
| `SNS_TOPIC_ARN` | `!Ref NotificationTopic` → topic ARN | `NotificationService` |
| `ENVIRONMENT` | `!Ref Environment` parameter (`prod`/`dev`) | Logging context |

None of these are hardcoded in source — they are always read from `System.getenv()` in the production constructors. The test constructors accept explicit values via constructor injection, so tests never touch environment variables.

---

## IAM permission boundaries

Two execution roles, one per Lambda function. Do not add permissions beyond what is listed here without a clear reason.

### `serverless-visitor-app-visitor-counter-role` (VisitorCounterFunction)

| Service | Allowed actions | Resource scope |
|---------|----------------|----------------|
| DynamoDB | `GetItem`, `UpdateItem`, `PutItem` | This table only |
| SQS | `SendMessage` | Main queue only |
| CloudWatch Logs | `CreateLogGroup`, `CreateLogStream`, `PutLogEvents` | Via `AWSLambdaBasicExecutionRole` |

### `serverless-visitor-app-sqs-processor-role` (SQSProcessorFunction)

| Service | Allowed actions | Resource scope |
|---------|----------------|----------------|
| SQS | `ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes`, `ChangeMessageVisibility` | Main queue only |
| SNS | `Publish` | This topic only |
| CloudWatch Logs | `CreateLogGroup`, `CreateLogStream`, `PutLogEvents` | Via `AWSLambdaBasicExecutionRole` |

Neither role has `dynamodb:DeleteItem`, `dynamodb:Scan`, `sqs:*`, `sns:*`, or any cross-account permissions. Any `AccessDeniedException` in the logs means the code is attempting an action not in the above lists.

---

## Test structure

Tests use JUnit 5 + Mockito. All AWS SDK calls are mocked — no real AWS resources, no Docker, no credentials needed.

### `VisitorCounterHandlerTest`

Injects a `@Mock VisitorService` into `VisitorCounterHandler(VisitorService)`. Covers:
- GET → returns `visitorCount` from service
- POST → calls `incrementAndQueueVisit()`, returns new count + message
- OPTIONS → returns 200 with CORS headers, no service call
- Unknown method → 405
- Service throws → 500
- CORS headers present on all response types

### `SQSProcessorHandlerTest`

Injects a `@Mock NotificationService` into `SQSProcessorHandler(NotificationService)`. Covers:
- Non-milestone counts → no SNS call
- Exact multiples of 100 → `sendNotification()` called with count in subject
- Malformed JSON → batch item failure returned
- Missing required fields → batch item failure returned
- `NotificationService` throws → batch item failure returned
- Mixed batch (good + bad messages) → only bad message IDs in failures
- Empty batch → empty failures list

When adding new behaviour: add a test to the relevant class before changing production code. The pattern is always `when(mock.method()).thenReturn(value)` → invoke handler → assert response + `verify(mock)`.

---

## Frontend wiring

```
frontend/config.js       sets window.API_BASE_URL  (gitignored, generated at deploy)
frontend/app.js          reads window.API_BASE_URL, appends /visit
frontend/index.html      loads config.js then app.js
```

`app.js` reads `window.API_BASE_URL` at startup. If it is falsy (config.js missing or empty), the page shows a warning banner and disables the button. All API calls go to `${API_BASE_URL}/visit`.

**After any Lambda or API Gateway change:** run `sam build && sam deploy` — the API URL does not change between deploys so `config.js` does not need to be regenerated unless the stack was deleted and recreated.

**After any frontend file change:** upload to S3 and invalidate CloudFront:

```bash
BUCKET=$(aws cloudformation describe-stacks --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='WebsiteBucketName'].OutputValue" --output text)
DIST_ID=$(aws cloudformation describe-stacks --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='CloudFrontDistributionId'].OutputValue" --output text)

aws s3 sync frontend/ s3://${BUCKET}/ --delete --cache-control "max-age=300"
aws cloudfront create-invalidation --distribution-id ${DIST_ID} --paths "/*"
```

---

## Stack parameters

Configured in `samconfig.toml` and passed via `--parameter-overrides`:

| Parameter | Default | Valid values | Notes |
|-----------|---------|-------------|-------|
| `NotificationEmail` | (required) | Any valid email | Must confirm SNS subscription after first deploy |
| `Environment` | `prod` | `dev`, `staging`, `prod` | Used as API Gateway stage name and log tag |
| `MonthlyBudgetLimit` | `10` | Any integer ≥ 1 | USD; alerts at 80% forecast and 100% actual |

The dev environment (`samconfig.toml` `[dev]` section) deploys to stack `serverless-visitor-app-dev` and does not set `NotificationEmail` — pass it as an override when deploying dev.

---

## Observability

### CloudWatch log groups (14-day retention)

| Log group | What it contains |
|-----------|-----------------|
| `/aws/lambda/serverless-visitor-app-visitor-counter` | One stream per Lambda container; INFO lines show counter values and SQS queue activity; WARN lines show non-fatal SQS failures |
| `/aws/lambda/serverless-visitor-app-sqs-processor` | Batch processing logs; shows milestone detection and SNS publish results |
| `/aws/apigateway/serverless-visitor-app` | Per-request JSON: `requestId`, `ip`, `method`, `path`, `status`, `latency` |

### CloudWatch alarms

| Alarm | Threshold | Sends email when |
|-------|-----------|-----------------|
| `serverless-visitor-app-lambda-invocation-spike` | > 1,000 invocations in 10 min on `visitor-counter` | Unusual traffic burst (crawler, abuse, load test) |
| `serverless-visitor-app-dlq-messages` | > 0 messages visible on DLQ | `SQSProcessorFunction` failed to process a message after 3 retries |

### Useful CloudWatch Logs Insights queries

```
# All errors in the last hour
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc | limit 50

# Non-fatal SQS send failures
fields @timestamp, @message
| filter @message like /SQS SendMessage failed/
| sort @timestamp desc | limit 20

# Cold starts and their duration
fields @timestamp, @initDuration, @duration
| filter @initDuration > 0
| sort @timestamp desc | limit 20

# Invocation rate per minute
fields @timestamp
| filter @message like /START RequestId/
| stats count() as invocations by bin(1m)
| sort @timestamp desc
```

---

## Important conventions

- **`sam build` before everything** — Lambda code is read from `.aws-sam/build/`, not `target/`.
- **Static AWS SDK clients** — `DynamoDbClient`, `SqsClient`, `SnsClient` are `static final` fields in each handler so they survive container reuse (connection pool warm-up). See ADR-007.
- **`UrlConnectionHttpClient`** is used instead of Apache HttpClient to reduce JAR size and cold-start latency. See ADR-008.
- **SQS partial batch failures** — `SQSProcessorHandler` returns `SQSBatchResponse` with `ReportBatchItemFailures`. Only genuinely failed messages are retried; don't catch exceptions silently if you want SQS to retry a message. See ADR-009.
- **SQS failure is non-fatal in `VisitorService`** — `enqueueVisitEvent()` catches `SqsException` and logs a warning rather than failing the HTTP response, because the counter has already been incremented. See `docs/flow.md` §3.
- **DynamoDB single-record design** — all traffic hits `pk = "VISITOR_COUNTER"`. The `ADD` expression is atomic so no application-level locking is needed. See ADR-010.
- **`config.js` is gitignored** — it is generated by the deploy script with the live API URL and must never be committed.
- **IAM least privilege** — each Lambda role only has the exact actions it needs (see `template.yaml` inline policies and ADR-001 through ADR-002 for the producer/consumer split rationale). Do not add `*` actions or broaden resource ARNs.
- **`DeletionPolicy: Retain` on DynamoDB** — the table survives `sam delete` / stack deletion to prevent data loss. **Side effect:** if you tear down the stack and redeploy, the retained table blocks a fresh stack creation — CloudFormation's `AWS::EarlyValidation::ResourceExistenceCheck` hook detects you're trying to CREATE a named resource that already exists and fails the changeset immediately. Fix: delete the table first (`aws dynamodb delete-table --table-name serverless-visitor-app-visitors && aws dynamodb wait table-not-exists --table-name serverless-visitor-app-visitors`), or import it into the new stack via a CloudFormation IMPORT changeset if you need to preserve the data.
- **`ApiGatewayAccount` resource is required for access logging** — API Gateway needs an IAM role ARN set at the account level before it can write access logs to CloudWatch Logs. Without `ApiGatewayCloudWatchRole` + `ApiGatewayAccount`, `VisitorApiStage` fails with "CloudWatch Logs role ARN must be set in account settings". `VisitorApi` has `DependsOn: ApiGatewayAccount` to enforce creation order. This is a one-per-account setting; if another stack in the same account already manages it, remove these resources to avoid conflicts.
- **No `ReservedConcurrentExecutions` on Lambda** — this account's total Lambda concurrency limit is too low to reserve slots without dropping below AWS's required 50-slot unreserved minimum. Cost protection is handled instead by API Gateway throttling (`ThrottlingRateLimit: 50`, `ThrottlingBurstLimit: 100`) and the CloudWatch `LambdaInvocationAlarm`. Do not add `ReservedConcurrentExecutions` back without first checking the account limit via `aws lambda get-account-settings`.
- **Java 17, not 21** — sdkman only has Corretto 17 installed (`sdk list java` to confirm). Both `pom.xml` and the `Runtime` in `template.yaml` are set to `java17`. Do not bump to `java21` without first installing it via `sdk install java 21.0.x-amzn`.

---

## Known failure modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| Changeset fails with `EarlyValidation::ResourceExistenceCheck` | DynamoDB table already exists from a previous deploy (retained by `DeletionPolicy: Retain`) | Delete the table manually, then redeploy |
| `VisitorApiStage` CREATE_FAILED: "CloudWatch Logs role ARN must be set" | `ApiGatewayAccount` not created first, or removed from the template | Ensure `ApiGatewayCloudWatchRole` + `ApiGatewayAccount` are in `template.yaml` and `VisitorApi` has `DependsOn: ApiGatewayAccount` |
| Stack stuck in `REVIEW_IN_PROGRESS` | Changeset created but not executed (e.g. deploy was interrupted) | `aws cloudformation delete-stack --stack-name serverless-visitor-app` |
| Frontend loads but counter shows `?` | `config.js` missing or contains wrong API URL | Regenerate `config.js` from stack outputs and re-sync to S3 |
| Counter increments but no milestone email | SNS subscription not confirmed | Click the confirmation link in the AWS notification email; check spam folder |
| `WARN SQS SendMessage failed` in logs | SQS call failed after DynamoDB increment (non-fatal) | Counter is correct; SQS message lost for that visit; investigate SQS queue health |
| `AccessDeniedException` in Lambda logs | Lambda attempting an action not in its IAM role | Compare the denied action against the inline policy in `template.yaml`; add only the specific action needed |
| SAM deploy fails with "uber-JAR not found" | `sam build` not run before `sam deploy` | Always run `sam build` first |
