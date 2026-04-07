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
- [`docs/sdk.md`](docs/sdk.md) — AWS SDK v2 client setup, every API call, request/response model, error handling
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
docs/
  architecture.md   Component diagram, data model, IAM boundaries
  decisions.md      ADR-001 through ADR-011 — every major technology choice
  flow.md           Step-by-step request and data flows including error paths
  sdk.md            AWS SDK for Java v2 — client setup, API calls, error handling
  console-guide.md  AWS Console monitoring reference (per-service)
template.yaml   All AWS resources (S3, CloudFront, API GW, Lambda, DDB, SQS, SNS, IAM)
samconfig.toml  SAM CLI deploy config (prod + dev environments)
pom.xml         Maven — produces uber-JAR via maven-shade-plugin
```

---

## First-time deploy checklist

Complete sequence for going from a fresh clone to a running application. Do not skip steps — each one depends on the previous.

### 1. Update `samconfig.toml`

`samconfig.toml` is committed with a placeholder email. **Before deploying**, replace it with a real address:

```toml
# [default.deploy.parameters]
parameter_overrides = "NotificationEmail=\"your-real@email.com\" Environment=\"prod\" MonthlyBudgetLimit=\"10\""
```

Do not commit the file after this change — it now contains a real email address. Reset it to `you@example.com` before staging the file for any commit.

### 2. Build and deploy

```bash
sam build
sam deploy   # uses samconfig.toml; confirm the changeset when prompted
```

### 3. Generate `config.js` and upload the frontend

```bash
API_URL=$(aws cloudformation describe-stacks --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" --output text)

BUCKET=$(aws cloudformation describe-stacks --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='WebsiteBucketName'].OutputValue" --output text)

DIST_ID=$(aws cloudformation describe-stacks --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='CloudFrontDistributionId'].OutputValue" --output text)

echo "window.API_BASE_URL = \"${API_URL}\";" > frontend/config.js
aws s3 sync frontend/ s3://${BUCKET}/ --delete --cache-control "max-age=300"
aws cloudfront create-invalidation --distribution-id ${DIST_ID} --paths "/*"
```

### 4. Confirm the SNS subscription

AWS sends a confirmation email to `NotificationEmail` when the stack is first created. Milestone notifications are silent until you click the confirmation link. Check your inbox and spam folder.

### 5. Verify

Open the `WebsiteUrl` output in a browser. The counter should load and increment on click.

---

## Creating `local-env.json` for local invocation

`local-env.json` is gitignored and must be generated from the deployed stack before running `sam local`. Run this once after each deploy (or whenever the stack is recreated):

```bash
SQS_URL=$(aws sqs get-queue-url \
  --queue-name serverless-visitor-app-visitor-queue \
  --query QueueUrl --output text)

SNS_ARN=$(aws sns list-topics \
  --query "Topics[?contains(TopicArn,'serverless-visitor-app-notifications')].TopicArn" \
  --output text)

cat > local-env.json <<EOF
{
  "VisitorCounterFunction": {
    "DYNAMODB_TABLE": "serverless-visitor-app-visitors",
    "SQS_QUEUE_URL": "${SQS_URL}",
    "SNS_TOPIC_ARN": "${SNS_ARN}",
    "ENVIRONMENT": "prod"
  },
  "SQSProcessorFunction": {
    "DYNAMODB_TABLE": "serverless-visitor-app-visitors",
    "SQS_QUEUE_URL": "${SQS_URL}",
    "SNS_TOPIC_ARN": "${SNS_ARN}",
    "ENVIRONMENT": "prod"
  }
}
EOF
```

`local-env.json` contains live AWS resource ARNs and must never be committed — it is already listed in `.gitignore`.

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

**Global** (both functions — `Globals.Function.Environment` in `template.yaml`):

| Variable | Resolved from | Used by |
|----------|--------------|---------|
| `DYNAMODB_TABLE` | `!Ref VisitorTable` → table name | `VisitorService` |
| `SQS_QUEUE_URL` | `!Ref VisitorQueue` → queue URL | `VisitorService` |
| `SNS_TOPIC_ARN` | `!Ref NotificationTopic` → topic ARN | `NotificationService` |
| `ENVIRONMENT` | `!Ref Environment` parameter (`prod`/`dev`) | Logging context |

**`VisitorCounterFunction` only** (function-level override in `template.yaml`):

| Variable | Resolved from | Used by |
|----------|--------------|---------|
| `CLOUDFRONT_URL` | `!Sub "https://${CloudFrontDistribution.DomainName}"` | `VisitorCounterHandler.corsHeaders()` — restricts `Access-Control-Allow-Origin` to the CloudFront origin |

`CLOUDFRONT_URL` is function-specific (not in Globals) because only `VisitorCounterHandler` sends HTTP responses with CORS headers. It falls back to `"*"` when absent (local `sam local` testing; set it in `local-env.json` under `"VisitorCounterFunction"` if you need accurate CORS locally).

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
- `visitorId` with embedded control characters → processed successfully (sanitized, not failed)
- CRLF in `visitorId` at a milestone → `\r` does not appear in the SNS notification

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

## Security hardening

This section documents every intentional security control in the codebase and why it exists. Read this before adding features that touch user input, logging, HTTP responses, or IAM.

### CloudFront response headers (`template.yaml` — `CloudFrontSecurityHeadersPolicy`)

A `AWS::CloudFront::ResponseHeadersPolicy` resource attaches security headers to every CloudFront response (including cached ones). No Lambda code is needed:

| Header | Value | What it prevents |
|--------|-------|-----------------|
| `Strict-Transport-Security` | `max-age=63072000; includeSubDomains; preload` | Protocol downgrade attacks; forces HTTPS for 2 years |
| `X-Content-Type-Options` | `nosniff` | MIME-sniffing attacks (browser executing a JS file served as `text/plain`) |
| `X-Frame-Options` | `DENY` | Clickjacking via `<iframe>` embedding |
| `X-XSS-Protection` | `1; mode=block` | Legacy XSS auditor for IE 11 (no effect on modern browsers) |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | URL leakage on cross-origin navigations |
| `Content-Security-Policy` | See below | XSS, data injection, script execution from unknown sources |

**CSP details:**

```
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
connect-src https://*.execute-api.<region>.amazonaws.com;
frame-ancestors 'none';
object-src 'none';
base-uri 'self'
```

- `style-src 'unsafe-inline'` is required because `index.html` has an inline `<style>` block. To remove `'unsafe-inline'`, move styles to a separate `.css` file.
- `connect-src` uses a **regional wildcard** (`*.execute-api.<region>.amazonaws.com`) rather than the specific API Gateway ID. Using `!Sub "${VisitorApi}.execute-api..."` would create a circular CloudFormation dependency: `CloudFrontDistribution` → `CloudFrontSecurityHeadersPolicy` → `VisitorApi` → `VisitorCounterFunction` → `CloudFrontDistribution`. Do not change this to reference `${VisitorApi}` directly.

### CORS restriction (`VisitorCounterHandler.java`)

`Access-Control-Allow-Origin` is set to the CloudFront domain (read from `CLOUDFRONT_URL` env var) rather than `*`. This prevents other websites from silently triggering counter increments via a user's browser. Non-browser clients (curl, scripts) are unaffected since they don't enforce CORS.

**How it works:**
- Production constructor reads `CLOUDFRONT_URL` from env. Falls back to `"*"` if the variable is absent (e.g. `sam local` without it set in `local-env.json`).
- Test constructor always uses `"*"` — no test changes needed.
- The `allowedOrigin` field is an instance variable (not static) so tests using the `VisitorService`-injection constructor get `"*"` automatically.

**If you add a custom domain:** update `CLOUDFRONT_URL` in `template.yaml` to match the new origin, or the browser's CORS check will fail.

### Jackson `StreamReadConstraints` (`SQSProcessorHandler.java`)

The `ObjectMapper` static initialiser sets hard limits appropriate for the flat, small JSON payloads this handler processes:

```java
StreamReadConstraints.builder()
    .maxStringLength(1_024)   // real IPs / timestamps are <50 chars
    .maxNestingDepth(4)        // payload is flat: {k:v, k:v, k:v}
    .maxNumberLength(20)       // long has at most 19 digits
    .build()
```

SQS already caps messages at 256 KB, but without these constraints a crafted message with a deeply nested or enormous JSON structure could exhaust Lambda memory before Jackson's own OOM protection triggers. Do not raise these limits without a concrete reason.

### Input sanitization (`SQSProcessorHandler.sanitize()`)

All string fields extracted from SQS message payloads (`visitorId`, `timestamp`) are passed through `sanitize()` before being logged or included in SNS notification bodies:

```java
private static String sanitize(String input) {
    if (input == null) return "";
    String cleaned = input.replaceAll("\\p{Cntrl}", "");  // strips 0x00–0x1F, 0x7F
    return cleaned.length() > 256 ? cleaned.substring(0, 256) : cleaned;
}
```

**Why this matters:**
- **Log injection** — a `visitorId` containing `\n` would create a fake new log line in CloudWatch, potentially hiding or forging audit events.
- **CRLF / email-header injection** — a `visitorId` like `1.2.3.4\r\nBcc:attacker@evil.com` could, in theory, inject email headers into the SNS notification if SNS passed the body verbatim (it doesn't currently, but sanitizing is the right defence-in-depth).
- **Length cap** — bounds the size of log lines and notification bodies regardless of what arrives in the SQS payload.

The source IP comes from API Gateway's request context (not user-supplied headers), so in practice these characters cannot appear in `visitorId`. Sanitization is defensive programming.

### Null-safe visitor ID resolution (`VisitorCounterHandler.resolveVisitorId()`)

Uses explicit null checks instead of a `catch (NullPointerException)` block:

```java
var ctx = request.getRequestContext();
if (ctx != null) {
    var identity = ctx.getIdentity();
    if (identity != null && identity.getSourceIp() != null) {
        return identity.getSourceIp();
    }
}
return "anon-" + System.currentTimeMillis();
```

Catching `NullPointerException` is an anti-pattern: it silently masks NPEs thrown from unrelated lines within the try block, making bugs invisible. Explicit null checks are unambiguous and don't suppress unexpected failures.

### DynamoDB parse guard (`VisitorService`)

Both `getVisitorCount()` and `atomicIncrement()` wrap `Long.parseLong()` in a try/catch for `NumberFormatException`. If the `count` attribute in DynamoDB is ever corrupted (manually edited, wrong type stored), the failure propagates as a `RuntimeException` with a clear diagnostic message rather than an opaque unchecked exception that bypasses the `DynamoDbException` catch.

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
- **Never catch `NullPointerException`** — use explicit null checks instead. Catching NPE hides bugs on unrelated lines in the same try block. See `resolveVisitorId()` for the pattern.
- **Sanitize before logging or forwarding** — any string sourced from external input (even indirectly via SQS) must pass through `SQSProcessorHandler.sanitize()` before it appears in a log line or notification. See the Security hardening section for rationale.
- **CloudFormation circular dependency risk** — `CloudFrontDistribution` → `CloudFrontSecurityHeadersPolicy` → `VisitorApi` → `VisitorCounterFunction` → `CloudFrontDistribution` is a latent cycle. The CSP `connect-src` uses a regional wildcard (`*.execute-api.<region>.amazonaws.com`) specifically to break this cycle. Do not change it to reference `!Sub "${VisitorApi}"`.
- **CORS fallback to `*` in local testing** — `VisitorCounterHandler` reads `CLOUDFRONT_URL` and falls back to `"*"` when the variable is absent. If you need accurate CORS behavior in `sam local`, add `"CLOUDFRONT_URL": "http://localhost:3000"` (or the CloudFront URL) to the `"VisitorCounterFunction"` section of `local-env.json`.
- **`DeletionPolicy: Retain` on DynamoDB** — the table survives `sam delete` / stack deletion to prevent data loss. **Side effect:** if you tear down the stack and redeploy, the retained table blocks a fresh stack creation — CloudFormation's `AWS::EarlyValidation::ResourceExistenceCheck` hook detects you're trying to CREATE a named resource that already exists and fails the changeset immediately. Fix: delete the table first (`aws dynamodb delete-table --table-name serverless-visitor-app-visitors && aws dynamodb wait table-not-exists --table-name serverless-visitor-app-visitors`), or import it into the new stack via a CloudFormation IMPORT changeset if you need to preserve the data.
- **`ApiGatewayAccount` resource is required for access logging** — API Gateway needs an IAM role ARN set at the account level before it can write access logs to CloudWatch Logs. Without `ApiGatewayCloudWatchRole` + `ApiGatewayAccount`, `VisitorApiStage` fails with "CloudWatch Logs role ARN must be set in account settings". `VisitorApi` has `DependsOn: ApiGatewayAccount` to enforce creation order. This is a one-per-account setting; if another stack in the same account already manages it, remove these resources to avoid conflicts.
- **No `ReservedConcurrentExecutions` on Lambda** — this account's total Lambda concurrency limit is too low to reserve slots without dropping below AWS's required 50-slot unreserved minimum. Cost protection is handled instead by API Gateway throttling (`ThrottlingRateLimit: 50`, `ThrottlingBurstLimit: 100`) and the CloudWatch `LambdaInvocationAlarm`. Do not add `ReservedConcurrentExecutions` back without first checking the account limit via `aws lambda get-account-settings`.
- **Java 17, not 21** — sdkman only has Corretto 17 installed (`sdk list java` to confirm). Both `pom.xml` and the `Runtime` in `template.yaml` are set to `java17`. Do not bump to `java21` without first installing it via `sdk install java 21.0.x-amzn`.

---

## Sensitive data rules

This repo has been audited for PII. The following rules must be maintained when making changes.

### Never commit

| Data type | Example of what NOT to commit | Safe placeholder to use instead |
|-----------|-------------------------------|--------------------------------|
| AWS account ID | `484484545317` | `123456789012` |
| API Gateway ID | `8bh4rypqpg` | `abc123def` |
| CloudFront domain | `d<random>.cloudfront.net` | `d1234abcdef.cloudfront.net` |
| CloudFront distribution ID | `E3R7FHZIX13A83` | `E1ABCDEF2GHIJK` |
| Real S3 bucket name | `serverless-visitor-app-website-484484545317` | `serverless-visitor-app-website-123456789012` |
| Real email address | `user@gmail.com` | `you@example.com` |
| Real SQS/SNS ARNs containing account ID | `arn:aws:sqs:us-east-1:484484545317:...` | Use `123456789012` as account segment |

### Gitignored files that contain real values

These files exist on disk but are never committed:

| File | What it contains | Why gitignored |
|------|-----------------|----------------|
| `frontend/config.js` | Live API Gateway URL | Generated at deploy time; changes with each stack recreation |
| `local-env.json` | Live SQS URL, SNS ARN | Contains resource ARNs from the deployed stack |

### `samconfig.toml` — the exception

`samconfig.toml` is committed but currently contains `you@example.com` as the `NotificationEmail` placeholder. It must be kept this way in version control. The workflow is:

1. Temporarily edit `samconfig.toml` with a real email to deploy
2. Run `sam deploy`
3. Reset `samconfig.toml` back to `you@example.com` before staging for a commit

### Before committing any change, scan for leaks

```bash
# Check for 12-digit AWS account IDs (exclude known safe timestamps)
grep -rn --include="*.md" --include="*.toml" --include="*.yaml" --include="*.json" \
  --include="*.java" --include="*.js" --include="*.html" \
  -E "[0-9]{12}" . | grep -v "123456789012\|1545082649\|1545082650\|20[0-9]{2}-[0-9]{2}-[0-9]{2}"

# Check for real email addresses
grep -rn --include="*.md" --include="*.toml" --include="*.yaml" --include="*.json" \
  --include="*.java" --include="*.js" --include="*.html" \
  -E "@[a-zA-Z0-9._%+-]+\.[a-zA-Z]{2,}" . \
  | grep -v "example\.com\|sns\.amazonaws\.com\|no-reply@\|you@"
```

Both commands should return no output from tracked files. If they do, replace the real values with the safe placeholders listed above before committing.

---

## Known failure modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| Changeset fails with `EarlyValidation::ResourceExistenceCheck` | DynamoDB table already exists from a previous deploy. This happens after `sam delete` (table is retained) **and** after any failed deploy that rolled back (the table was created during deployment then retained by rollback, so the next deploy attempt finds it already there — this can repeat on every failed attempt). | Delete the table (`aws dynamodb delete-table --table-name serverless-visitor-app-visitors && aws dynamodb wait table-not-exists --table-name serverless-visitor-app-visitors`), then redeploy |
| `VisitorApiStage` CREATE_FAILED: "CloudWatch Logs role ARN must be set" | `ApiGatewayAccount` not created first, or removed from the template | Ensure `ApiGatewayCloudWatchRole` + `ApiGatewayAccount` are in `template.yaml` and `VisitorApi` has `DependsOn: ApiGatewayAccount` |
| Stack stuck in `REVIEW_IN_PROGRESS` | Changeset created but not executed (e.g. deploy was interrupted) | `aws cloudformation delete-stack --stack-name serverless-visitor-app` |
| Frontend loads but counter shows `?` | `config.js` missing or contains wrong API URL | Regenerate `config.js` from stack outputs and re-sync to S3 |
| Counter increments but no milestone email | SNS subscription not confirmed | Click the confirmation link in the AWS notification email; check spam folder |
| `WARN SQS SendMessage failed` in logs | SQS call failed after DynamoDB increment (non-fatal) | Counter is correct; SQS message lost for that visit; investigate SQS queue health |
| `AccessDeniedException` in Lambda logs | Lambda attempting an action not in its IAM role | Compare the denied action against the inline policy in `template.yaml`; add only the specific action needed |
| SAM deploy fails with "uber-JAR not found" | `sam build` not run before `sam deploy` | Always run `sam build` first |
