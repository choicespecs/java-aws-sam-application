# AWS Console Guide

A detailed, service-by-service reference for understanding and monitoring this application through the AWS Console. Each section explains what the service is doing in this architecture, what you should see, what healthy looks like, and how to diagnose problems.

All resources are in **us-east-1**. Resource names use the stack name `serverless-visitor-app` as a prefix.

---

## How to read this guide

The application has two independent runtime paths:

**Synchronous path** (user-facing, latency-sensitive):
```
Browser → CloudFront → S3              (page load)
Browser → API Gateway → Lambda → DynamoDB  (GET /visit — read counter)
Browser → API Gateway → Lambda → DynamoDB + SQS  (POST /visit — increment counter)
```

**Asynchronous path** (background, decoupled from the API response):
```
SQS → Lambda → SNS → Email            (milestone notifications)
```

When something breaks, identify which path is affected first, then follow it through the services below.

---

## CloudFormation

**Console path:** `Services → CloudFormation → Stacks → serverless-visitor-app`

CloudFormation is the deployment record for every AWS resource this application uses. Start here whenever you need to find a resource, understand the current deployed state, or diagnose a failed deploy.

### Stack info tab

The **Stack status** field tells you the outcome of the last `sam deploy`:

| Status | Meaning |
|--------|---------|
| `CREATE_COMPLETE` | First deploy succeeded — all resources exist |
| `UPDATE_COMPLETE` | A subsequent deploy succeeded |
| `UPDATE_ROLLBACK_COMPLETE` | Last deploy failed; stack is back to its previous state |
| `ROLLBACK_COMPLETE` | First deploy failed; stack exists but has no resources (must be deleted before redeploying) |
| `REVIEW_IN_PROGRESS` | A changeset exists but has not been executed |

### Events tab

A chronological log of every resource operation. This is the primary tool for diagnosing a failed deploy.

**How to read it:** Events are listed newest-first. A failed deploy shows a `CREATE_FAILED` or `UPDATE_FAILED` event for the resource that actually broke, followed by `ROLLBACK_IN_PROGRESS` and `DELETE_*` events as CloudFormation cleans up. The root cause is always the first `*_FAILED` event (before the rollback cascade).

Filter the list by typing `FAILED` in the search box to isolate the problem event. The **Status reason** column contains the exact AWS error message.

### Resources tab

Lists every resource the stack manages with its physical ID and a direct link to its page in the native console. Use this to navigate to any resource without memorizing ARNs.

Resources in this stack:

| Logical ID | Type | What it is |
|-----------|------|-----------|
| `WebsiteBucket` | S3 Bucket | Frontend file storage |
| `WebsiteBucketPolicy` | S3 BucketPolicy | Restricts bucket access to CloudFront only |
| `CloudFrontOAC` | CloudFront OriginAccessControl | Signed S3 request credential |
| `CloudFrontDistribution` | CloudFront Distribution | CDN + HTTPS termination |
| `VisitorTable` | DynamoDB Table | Visitor counter storage |
| `VisitorQueue` | SQS Queue | Visit event buffer |
| `VisitorQueueDLQ` | SQS Queue | Failed message capture |
| `NotificationTopic` | SNS Topic | Milestone email delivery |
| `ApiGatewayCloudWatchRole` | IAM Role | Allows API Gateway to write to CloudWatch |
| `ApiGatewayAccount` | ApiGateway Account | Account-level CloudWatch logging setting |
| `ApiGatewayLogGroup` | CloudWatch LogGroup | API Gateway access logs |
| `VisitorApi` | ApiGateway RestApi | `/visit` endpoint |
| `VisitorCounterFunction` | Lambda Function | GET / POST handler |
| `VisitorCounterLogGroup` | CloudWatch LogGroup | Lambda function logs |
| `VisitorCounterRole` | IAM Role | Lambda execution permissions |
| `SQSProcessorFunction` | Lambda Function | SQS → SNS processor |
| `SQSProcessorLogGroup` | CloudWatch LogGroup | Lambda function logs |
| `SQSProcessorRole` | IAM Role | Lambda execution permissions |
| `MonthlyBudget` | Budgets Budget | Spend alert |
| `LambdaInvocationAlarm` | CloudWatch Alarm | Traffic spike alert |
| `DLQDepthAlarm` | CloudWatch Alarm | Failed message alert |

### Outputs tab

The live URLs and identifiers for all externally-referenced resources. Copy values from here rather than constructing them manually.

| Key | Value example | Use |
|-----|--------------|-----|
| `WebsiteUrl` | `https://d1234abcdef.cloudfront.net` | Open in browser to see the site |
| `ApiUrl` | `https://abc123def.execute-api.us-east-1.amazonaws.com/prod` | API endpoint; append `/visit` |
| `WebsiteBucketName` | `serverless-visitor-app-website-123456789012` | S3 sync target |
| `CloudFrontDistributionId` | `E1ABCDEF2GHIJK` | Cache invalidation target |
| `DLQUrl` | `https://sqs.us-east-1.amazonaws.com/...` | Monitor for non-zero depth |

### Parameters tab

Confirms the values set at deploy time. Check here if you suspect the wrong email address or environment name was used.

| Parameter | Expected value |
|-----------|---------------|
| `NotificationEmail` | Your email — must match the confirmed SNS subscription |
| `Environment` | `prod` (or `dev` for the dev stack) |
| `MonthlyBudgetLimit` | `10` (USD) |

---

## S3

**Console path:** `Services → S3 → serverless-visitor-app-website-<account-id>`

S3 stores the three frontend files. The bucket is private — all public access is blocked. CloudFront is the only entity allowed to read from it, enforced by the bucket policy.

### Objects tab

After a frontend deploy (`aws s3 sync frontend/ s3://...`), these three files must exist:

| File | Purpose | What to verify |
|------|---------|----------------|
| `index.html` | Page structure and layout | Present and recently modified |
| `app.js` | API fetch logic and UI behaviour | Present and recently modified |
| `config.js` | Sets `window.API_BASE_URL` | Present; click to download and confirm URL matches the `ApiUrl` output |

**`config.js` is the most common source of silent failures.** If it's missing, contains a wrong URL, or contains `undefined`, the page will load but every API call will fail. Click the file → **Download** → open in a text editor and confirm the content looks like:

```js
window.API_BASE_URL = "https://abc123def.execute-api.us-east-1.amazonaws.com/prod";
```

Click any file → **Properties** tab to see its **Last modified** timestamp. If this predates your last deploy, the sync did not complete.

### Properties tab

**Bucket Versioning:** Should show `Enabled`. Versioning means if you accidentally overwrite `index.html` with a bad file, you can recover the previous version by going to the Objects tab, clicking **Show versions**, and restoring the previous version ID.

**Default encryption:** Should show `Server-side encryption with Amazon S3 managed keys (SSE-S3)` — AES-256 at rest.

**Public access settings:** All four "Block public access" settings should be **On**. If any are off, S3 objects could be directly accessible, bypassing CloudFront.

### Permissions tab

**Bucket policy** — click to view the raw JSON. The policy should have exactly one statement:
- Effect: `Allow`
- Principal: `cloudfront.amazonaws.com`
- Action: `s3:GetObject`
- Resource: `arn:aws:s3:::serverless-visitor-app-website-<account-id>/*`
- Condition: `AWS:SourceArn` matching this specific CloudFront distribution ARN

The `AWS:SourceArn` condition is the security-critical part. Without it, any CloudFront distribution in the world (not just yours) could read your bucket. With it, only this distribution can.

### Metrics tab

Available after enabling **Request metrics** (not enabled by default — adds a small cost). Without it, the tab shows only storage size and object count.

| Metric | Expected |
|--------|---------|
| **BucketSizeBytes** | A few kilobytes (three small text files) |
| **NumberOfObjects** | 3 (or 4+ if you have versioning history) |

---

## CloudFront

**Console path:** `Services → CloudFront → Distributions → (select by domain or ID)`

CloudFront sits between the browser and S3, providing HTTPS termination, edge caching, and geographic distribution. It is also the entry point for the frontend — the `WebsiteUrl` output is the CloudFront domain.

### General tab

**Distribution status** must be `Enabled`. If it shows `Disabled`, the site is unreachable.

**Last modified** — changes any time the distribution configuration is updated (e.g., after a deploy that modifies `template.yaml`). Note that CloudFront configuration changes take 5–15 minutes to propagate to all edge nodes worldwide.

**Price class** is set to `PriceClass_100`, meaning edge nodes in North America and Europe. Visitors from Asia-Pacific or South America will be routed to the geographically nearest `PriceClass_100` node, which is still faster than going directly to S3 in us-east-1.

### Origins tab

One origin is configured: the S3 bucket, accessed via **Origin Access Control (OAC)**. The **Origin access** column should show the OAC name (`serverless-visitor-app-oac`), not "Public" or an Origin Access Identity.

OAC signs every request to S3 with SigV4 using CloudFront's service principal. This means S3 can verify the request came from this specific CloudFront distribution — the basis for the bucket policy condition.

### Behaviors tab

One behavior covers all paths (`Default (*)`):

| Setting | Value | Why |
|---------|-------|-----|
| Viewer protocol policy | Redirect HTTP to HTTPS | Forces encrypted connections |
| Cache policy | CachingOptimized | Caches on Accept-Encoding, compresses at edge |
| Allowed HTTP methods | GET, HEAD, OPTIONS | Static content only; POST is not forwarded to S3 |
| Compress objects automatically | Yes | Brotli/gzip for text files |

### Error pages tab

Two custom error responses are configured. These exist because S3 returns `403` (not `404`) when a key does not exist in a private bucket:

| HTTP error code | Response code | Response page |
|----------------|---------------|---------------|
| 403 | 200 | /index.html |
| 404 | 200 | /index.html |

This enables client-side routing — navigating directly to `/about` or refreshing a page at any URL serves `index.html` and lets JavaScript handle the path, rather than showing an AWS XML error page.

### Invalidations tab

Lists all cache invalidation operations. After running `aws cloudfront create-invalidation`, the invalidation appears here with status `In Progress` and then `Completed` within a few minutes.

If a frontend update is not showing for users after a deploy, check this tab. If the latest invalidation is still `In Progress`, edge nodes are still serving cached files. If no recent invalidation exists, you may have skipped the `aws cloudfront create-invalidation` step.

### Monitoring tab

These metrics are available at no extra cost. Time range is adjustable.

| Metric | Healthy range | What a problem looks like |
|--------|--------------|--------------------------|
| **Requests** | Varies | Unexpected spike may indicate scraping or an attack |
| **Cache hit rate** | > 85% | Below 50% means most requests are missing the cache and hitting S3 — check if `Cache-Control` headers are set correctly |
| **4xx error rate** | < 0.5% | Sustained high rate means files are missing or the bucket policy is broken |
| **5xx error rate** | ~0% | Means S3 is returning errors — check the bucket and its policy |
| **Total error rate** | < 1% | Combined 4xx + 5xx |
| **Bytes downloaded** | Varies | Measures egress — relevant for understanding CloudFront costs |

**Where the cache hit rate matters for cost:** CloudFront charges per request that reaches the origin (S3). A high cache hit rate means fewer origin requests and lower S3 costs. Static files (`index.html`, `app.js`) should cache well; `config.js` contains the API URL which never changes between deploys, so it should also cache well.

---

## API Gateway

**Console path:** `Services → API Gateway → serverless-visitor-app-api`

API Gateway provides the stable HTTPS `/visit` endpoint that the frontend calls. It handles CORS pre-flights, enforces rate limiting, and forwards requests to Lambda.

### Resources panel (left sidebar)

Shows the resource tree. You should see:

```
/ (root)
└── /visit
    ├── GET    → VisitorCounterFunction
    ├── POST   → VisitorCounterFunction
    └── OPTIONS → VisitorCounterFunction
```

Click any method (GET, POST, OPTIONS) to see its integration:
- **Integration type:** Lambda Proxy
- **Lambda function:** `serverless-visitor-app-visitor-counter`
- **Use Lambda proxy integration:** Yes — the full HTTP request is passed as a JSON event to Lambda, and Lambda's return value is used as the HTTP response

#### Testing a method from the console

1. Click `GET` under `/visit`
2. Click the **Test** button (lightning bolt icon)
3. Leave the request body empty
4. Click **Test**
5. The right panel shows the raw Lambda response, headers, and logs

This is a direct Lambda invocation through API Gateway — it uses real DynamoDB and is the fastest way to verify the API is working without `curl`.

### Stages → prod

The stage represents a deployed snapshot of the API.

**Invoke URL** shown at the top of this page should match the `ApiUrl` stack output.

#### Stage-level throttling

Click the `prod` stage → **Throttling** to confirm:
- **Default method throttling:** Rate = 50 req/s, Burst = 100 req/s

These limits apply to the entire API. If a request is throttled, API Gateway returns `429 Too Many Requests` without invoking Lambda. The `LambdaInvocationAlarm` threshold (1,000 invocations in 10 minutes ≈ 1.7 req/s) is well below the 50 req/s rate limit, so the alarm fires before throttling would become significant.

#### Logs / Tracing

Click the `prod` stage → **Logs/Tracing**:
- **CloudWatch Logs:** Should be `INFO` with log group `/aws/apigateway/serverless-visitor-app`
- **X-Ray Tracing:** Should be `Enabled`

If either is disabled, access logs and traces will not be written — edit the stage settings to re-enable.

### Dashboard

**Console path:** `API Gateway → serverless-visitor-app-api → Dashboard`

Displays stage metrics for a selected time window.

| Metric | Healthy | Investigate when |
|--------|---------|-----------------|
| **Calls** | Steady or growing | Sudden drop to zero may indicate DNS or CloudFront issue |
| **4XX Errors** | Near zero | `400` = malformed request; `403` = auth issue; `429` = throttled |
| **5XX Errors** | Zero | `502` = Lambda returned an invalid response; `503` = Lambda throttled; `504` = Lambda timeout |
| **Latency** | p50 < 200 ms, p99 < 3 s | p99 spikes indicate Lambda cold starts |
| **Integration latency** | p50 < 150 ms | Time spent inside Lambda — excludes API Gateway overhead |

The difference between **Latency** (total round-trip at API Gateway) and **Integration latency** (time in Lambda) tells you where time is being spent. If latency is high but integration latency is low, the overhead is in API Gateway itself.

---

## Lambda

**Console path:** `Services → Lambda → Functions`

Two functions handle all compute in this application:

| Function | Trigger | What it does |
|----------|---------|-------------|
| `serverless-visitor-app-visitor-counter` | API Gateway (sync) | Reads or increments the DynamoDB counter; sends SQS message |
| `serverless-visitor-app-sqs-processor` | SQS (async) | Processes visit events; sends SNS email on milestone |

### Understanding Lambda execution

Lambda runs code in a managed container. The first invocation after a period of inactivity incurs a **cold start** — the container is initialized from scratch, the JVM starts, and static class fields (the AWS SDK clients) are created. This adds 2–8 seconds to the first request. Subsequent invocations reuse the same container (**warm start**) and are much faster.

The `static final` `DynamoDbClient`, `SqsClient`, and `SnsClient` fields in each handler are the key cold-start optimization — they survive container reuse. The JVM also JIT-compiles hot paths over time, so throughput improves after the first few invocations.

### VisitorCounterFunction

**Console path:** `Lambda → serverless-visitor-app-visitor-counter`

#### Code tab

- **Runtime:** Java 17
- **Handler:** `com.example.handlers.VisitorCounterHandler::handleRequest`
- **Architecture:** x86_64

You cannot view the source code here (the deployment artifact is a compiled JAR), but you can see the package structure. The **Code source** panel shows the `.aws-sam/build` artifact.

#### Configuration tab

**General configuration:**
- **Memory:** 512 MB — enough for the JVM with headroom; increasing this also allocates proportionally more vCPU
- **Timeout:** 30 s — the maximum time a single invocation can run; API Gateway will return a `504` if Lambda does not respond within this window

**Environment variables:** Confirm these are set and contain live values (not placeholders):

| Variable | What it should contain |
|----------|----------------------|
| `DYNAMODB_TABLE` | `serverless-visitor-app-visitors` |
| `SQS_QUEUE_URL` | `https://sqs.us-east-1.amazonaws.com/<account-id>/serverless-visitor-app-visitor-queue` |
| `SNS_TOPIC_ARN` | `arn:aws:sns:us-east-1:<account-id>:serverless-visitor-app-notifications` |
| `ENVIRONMENT` | `prod` |

If any of these are missing or wrong, the function will fail at runtime with a `NullPointerException` or SDK configuration error.

**Permissions:** The execution role is `serverless-visitor-app-visitor-counter-role`. Click the role name to open IAM and inspect its exact permissions (see the IAM section below).

**Triggers:** Should show one trigger: `API Gateway - serverless-visitor-app-api`

#### Monitor tab

The six graphs here show the last 24 hours by default. Change the time range to investigate a specific incident.

| Metric | What it measures | What to look for |
|--------|-----------------|-----------------|
| **Invocations** | Total calls (success + error) | Matches API Gateway's "Calls" count; a gap between the two means requests are being throttled before reaching Lambda |
| **Duration** | Execution time in ms | p50 should be < 200 ms warm; spikes to > 2,000 ms = cold start or slow DynamoDB; flat line at 30,000 ms = timeout |
| **Error count and success rate** | Unhandled exceptions | Any non-zero errors warrant checking the logs |
| **Throttles** | Requests rejected by Lambda (not API Gateway) | Non-zero means account concurrency is exhausted |
| **Async delivery failures** | N/A for this function (sync invocation) | — |
| **Concurrent executions** | Active containers at any moment | Stays near 1 for low-traffic personal apps |

#### Testing from the console

1. Click the **Test** tab
2. Select **Create new event** → name it `GetVisit`
3. Choose **apigateway-aws-proxy** from the template dropdown
4. Modify `"httpMethod"` to `"GET"` and delete the `"body"` key
5. Click **Test**
6. Expand **Execution result** — look for `"statusCode": 200` and `"visitorCount"` in the response body

For a POST test, set `"httpMethod": "POST"` and optionally set `"body": "{}"`.

---

### SQSProcessorFunction

**Console path:** `Lambda → serverless-visitor-app-sqs-processor`

#### Configuration tab → Triggers

The trigger is the SQS event source mapping. Click the trigger to see its configuration:

| Setting | Value | Why it matters |
|---------|-------|----------------|
| **Batch size** | 10 | Up to 10 messages are processed per invocation; reduces invocation count and cost |
| **Batching window** | 5 seconds | Lambda waits up to 5 s to accumulate a full batch; reduces invocations during low traffic |
| **Function response type** | `ReportBatchItemFailures` | Lambda returns failed message IDs individually so only those are retried |
| **State** | `Enabled` | If disabled, the queue fills and is never drained |

If the trigger state is `Disabled`, messages accumulate in the main queue indefinitely. Enable it from this page.

#### Monitor tab — Iterator age

**Iterator age** is the most important metric for the SQS processor. It measures how old the oldest unprocessed message in the queue is — i.e., the lag between when a message was sent and when Lambda actually processes it.

| Iterator age | What it means |
|-------------|---------------|
| 0–5 s | Healthy — Lambda is keeping up with the queue |
| 5–60 s | Slight backlog — likely a burst of traffic |
| > 60 s | Lambda is behind — check for errors, throttles, or a surge in queue depth |
| Growing continuously | Lambda is not draining the queue — likely an error causing retries |

A growing iterator age combined with DLQ messages means Lambda is failing on every message and retrying until they are moved to the DLQ.

---

## DynamoDB

**Console path:** `Services → DynamoDB → Tables → serverless-visitor-app-visitors`

DynamoDB stores the single visitor counter record. This is the system of record — every GET reads it, every POST writes to it atomically.

### Overview tab

| Property | Expected value |
|----------|---------------|
| **Status** | `Active` |
| **Billing mode** | `Pay per request (on-demand)` |
| **Table class** | `DynamoDB Standard` |
| **Partition key** | `pk` (String) |
| **Point-in-time recovery** | `Enabled` |
| **Encryption** | `Owned by Amazon DynamoDB` (SSE enabled) |

### Explore items (formerly "Items" tab)

Click **Explore table items**. The table uses a single-record design — you should see exactly one item:

| pk (S) | count (N) | lastUpdated (S) |
|--------|-----------|----------------|
| `VISITOR_COUNTER` | `42` | `2024-01-15T10:30:00.123Z` |

**If the table is empty:** No POST request has ever succeeded. The counter is created on first write — `UpdateItem` with `ADD` creates the item and the attribute if neither exists.

**If `count` is unexpectedly large:** Multiple clients or test scripts may have been sending POST requests. The counter is a monotonically increasing number with no reset mechanism other than a manual edit.

**If `lastUpdated` is stale:** The last POST was a long time ago (or never — the attribute is set during UpdateItem). This is purely informational.

#### Manually editing the counter

1. Click the `VISITOR_COUNTER` item row
2. Click **Actions → Edit item**
3. Find the `count` attribute — click its value to edit it
4. Change to any number (e.g., `0` to reset) and click **Save changes**

This is a direct write — it bypasses Lambda and takes effect immediately on the next GET.

### Metrics tab

Scroll down to **CloudWatch metrics for this table**.

| Metric | What it shows | Normal pattern |
|--------|--------------|----------------|
| **Consumed read capacity** | DynamoDB read units consumed by GetItem | Small spikes each time GET /visit is called |
| **Consumed write capacity** | DynamoDB write units consumed by UpdateItem | Small spikes each time POST /visit is called; on-demand so never throttled |
| **Successful request latency (GetItem)** | How fast reads complete | Should be 1–5 ms for a single-item key lookup |
| **Successful request latency (UpdateItem)** | How fast writes complete | Should be 2–8 ms |
| **System errors** | Internal DynamoDB failures | Should always be 0 |
| **User errors** | Requests rejected due to bad input | Should be 0; non-zero means the Lambda code is sending malformed requests |
| **Throttled requests** | Rejected due to capacity | Should be 0 — on-demand billing means DynamoDB scales automatically |

### Backups tab

**Point-in-time recovery** should show status `Enabled` with an **Earliest restorable time** roughly 35 days ago (or since the table was created if less than 35 days). This gives a rolling window to restore the table to any specific second within that range.

To restore from PITR:
1. Click **Restore to point in time**
2. Enter the exact date and time you want to restore to
3. Provide a new table name (the restored table is a separate table — the original is not affected)
4. After the restore, you can read the recovered item from the new table and use the count value to manually correct the live table

### Global Tables tab

Not configured — the counter is in a single region. This is appropriate for a personal project where multi-region replication is not needed.

---

## SQS

**Console path:** `Services → SQS`

Two queues decouple the synchronous API path from the asynchronous notification path.

### Main queue: `serverless-visitor-app-visitor-queue`

Click the queue name to open its detail page.

#### Details panel

| Property | Value | Why |
|----------|-------|-----|
| **Type** | Standard | Standard queues have at-least-once delivery and best-effort ordering; the application tolerates occasional duplicate processing |
| **Visibility timeout** | 180 s | While Lambda processes a message, the message is hidden from other consumers for 180 s. Set to 6× the Lambda timeout (6 × 30 s) so Lambda has time to finish before SQS assumes the message was lost |
| **Message retention period** | 1 day | Visit events have no value after they have been processed; short retention minimises storage cost |
| **Maximum message size** | 256 KB (default) | SQS visit event messages are ~150 bytes — far below the limit |
| **Delivery delay** | 0 s | Messages are immediately visible to consumers |
| **Redrive policy (DLQ)** | Enabled; max receive count = 3 | After 3 failed processing attempts, SQS moves the message to the DLQ instead of discarding it |
| **Encryption** | SSE-SQS | Messages at rest are encrypted with an SQS-managed key |

#### Monitoring tab

| Metric | Healthy | Investigate when |
|--------|---------|-----------------|
| **Messages sent** | Rises with POST /visit traffic | Zero for a long period = VisitorCounterFunction is not calling SQS (check Lambda logs for `WARN SQS SendMessage failed`) |
| **Messages received** | Should closely track messages sent | Big gap = SQSProcessorFunction trigger is disabled or not running |
| **Messages deleted** | Should equal messages received (after processing) | Zero while received is non-zero = Lambda is receiving but not successfully deleting (processing is failing) |
| **Approximate age of oldest message** | Seconds | Growing continuously = Lambda is not draining the queue |
| **Approximate number of messages visible** | 0–10 (bursts) | Sustained > 0 = processing lag |
| **Approximate number of messages not visible** | 0–10 (briefly) | Messages currently being processed by Lambda |

#### Sending a test message

To manually trigger SQSProcessorFunction without going through the API:

1. Click **Send and receive messages**
2. In the **Message body** field, paste a valid visit event JSON:
   ```json
   {"visitorId": "test-user", "visitorCount": 100, "timestamp": "2024-01-01T00:00:00Z"}
   ```
3. Click **Send message**
4. Lambda will process it within ~5 seconds (the batching window)
5. Because `visitorCount` is `100`, this will trigger an SNS email

This is useful for testing the milestone notification path end-to-end without needing 100 real visitors.

---

### Dead letter queue: `serverless-visitor-app-visitor-dlq`

**This queue should always be empty.** Any message here represents a visit event that SQSProcessorFunction failed to process three times.

#### Details panel

| Property | Value |
|----------|-------|
| **Message retention period** | 14 days | Long enough to investigate root cause and replay after a fix |
| **Redrive allow policy** | Main queue is the source queue |

#### Monitoring tab

Check **Approximate number of messages visible**. The value should be `0`.

A non-zero value triggers the `serverless-visitor-app-dlq-messages` CloudWatch alarm and sends an email.

#### Inspecting a failed message

1. Click **Send and receive messages**
2. Scroll to **Receive messages** → click **Poll for messages**
3. A message appears in the list — click it to expand
4. The **Body** tab shows the raw SQS message (the visit event JSON that the processor could not handle)
5. The **Attributes** tab shows `ApproximateReceiveCount: 3` — confirming it was retried 3 times before being moved here

Cross-reference the `timestamp` field in the message body with the SQSProcessorFunction CloudWatch logs to find the specific error.

#### Replaying after a fix

Once you have deployed a fix to SQSProcessorFunction:

1. Open the DLQ page
2. Click **Start DLQ redrive**
3. Select **Redrive to source queue** — the main queue
4. Click **Redrive**

SQS moves messages from the DLQ back to the main queue, and Lambda reprocesses them.

---

## SNS

**Console path:** `Services → SNS → Topics → serverless-visitor-app-notifications`

SNS delivers the milestone email notifications. It is only invoked by SQSProcessorFunction when `visitorCount % 100 == 0`.

### Details panel

| Property | Value |
|----------|-------|
| **Type** | Standard |
| **ARN** | `arn:aws:sns:us-east-1:<account-id>:serverless-visitor-app-notifications` |
| **Encryption** | Disabled (not needed for non-sensitive notification content) |
| **Access policy** | Default — only the account can publish to this topic |

### Subscriptions panel

One subscription should be listed with your email address.

| Field | Expected value |
|-------|---------------|
| **Protocol** | `Email` |
| **Endpoint** | Your email address (from `NotificationEmail` parameter) |
| **Status** | **`Confirmed`** |

**If status is `Pending confirmation`:** AWS sent a confirmation email when the stack was first created. The subscription is inactive until you click the confirmation link in that email. Check your spam folder. The email subject is "AWS Notification - Subscription Confirmation".

To re-send the confirmation:
1. Click the subscription ARN link
2. Click **Request confirmation** button

**If the subscription is `Confirmed` but emails are not arriving:** The SNS publish may be failing. Check SQSProcessorFunction CloudWatch logs for errors around the `Publish` call.

### Publish message (manual test)

To send a test notification directly:
1. Click **Publish message**
2. **Subject:** `Test milestone notification`
3. **Message body:** Any text
4. Click **Publish message**

You should receive an email within 1–2 minutes. If you do not, the subscription is either not confirmed or the email is going to spam.

### Monitoring

From the topic page, click **View CloudWatch metrics** to see:

| Metric | What it shows |
|--------|---------------|
| `NumberOfMessagesPublished` | SNS publishes from SQSProcessorFunction — should be 1 per milestone |
| `NumberOfNotificationsDelivered` | Successful deliveries to the email subscriber |
| `NumberOfNotificationsFailed` | Failed deliveries — check subscription status if non-zero |
| `NumberOfNotificationsFilteredOut` | Messages filtered by subscription filter policy (none configured here) |

---

## CloudWatch

**Console path:** `Services → CloudWatch`

CloudWatch aggregates logs and metrics from every other service. It is the primary diagnostic tool for runtime issues.

### Log groups

**Console path:** `CloudWatch → Log groups`

Three log groups are created by this stack, each with a 14-day retention period:

| Log group | Populated by | What the entries contain |
|-----------|-------------|--------------------------|
| `/aws/lambda/serverless-visitor-app-visitor-counter` | VisitorCounterFunction | One log stream per Lambda container; each invocation logged within its stream |
| `/aws/lambda/serverless-visitor-app-sqs-processor` | SQSProcessorFunction | Same structure; each invocation processes up to 10 SQS messages |
| `/aws/apigateway/serverless-visitor-app` | API Gateway | One JSON line per HTTP request with IP, method, path, status, latency |

#### Reading Lambda log streams

1. Click a Lambda log group
2. Click **Log streams** — each row is one Lambda container; there may be one or several depending on concurrency
3. Sort by **Last event time** to find the most recent activity
4. Click a stream to see individual log entries

Each invocation within a stream is surrounded by `START` and `END` markers with the request ID:

```
START RequestId: abc-123  Version: $LATEST
INFO  Received GET /visit  requestId=abc-123
INFO  Current visitor count: 42
END RequestId: abc-123
REPORT RequestId: abc-123  Duration: 145.23 ms  Billed Duration: 146 ms  Memory Size: 512 MB  Max Memory Used: 187 MB  Init Duration: 4521.33 ms
```

The `REPORT` line shows:
- **Duration:** Actual execution time
- **Billed duration:** Rounded up to the nearest ms (the billable unit)
- **Max memory used:** Peak JVM heap — if this approaches 512 MB, consider increasing the memory setting
- **Init duration:** Only present on cold starts — the JVM startup time (typically 3–6 s for this Java 17 handler)

Log levels used in this application:
- `INFO` — normal operation: counter values, queue activity
- `WARN` — non-fatal issues: `SQS SendMessage failed (non-fatal)` — means a visit was counted but no SQS message was sent
- `ERROR` — failures that resulted in an error response: DynamoDB unreachable, unhandled exceptions

#### Reading API Gateway access logs

Access log entries are structured JSON, one object per HTTP request:

```json
{
  "requestId": "abc-123",
  "ip": "203.0.113.42",
  "method": "POST",
  "path": "/visit",
  "status": "200",
  "latency": "145"
}
```

Use these logs to see exactly which IPs are calling the API, what status codes they received, and how long each request took. This is useful for detecting scraping or unexpected traffic patterns.

---

### CloudWatch Logs Insights

**Console path:** `CloudWatch → Logs Insights`

Logs Insights runs SQL-like queries across one or more log groups. Select a log group in the top dropdown, set a time range, and run a query.

#### Useful queries for this application

**Find all errors in the last hour:**
```
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc
| limit 50
```

**Find SQS send failures (non-fatal, but worth knowing about):**
```
fields @timestamp, @message
| filter @message like /SQS SendMessage failed/
| sort @timestamp desc
| limit 20
```

**Find all cold starts and their duration:**
```
fields @timestamp, @initDuration, @duration
| filter @initDuration > 0
| sort @timestamp desc
| limit 20
```

**Find slow requests (over 1 second):**
```
fields @timestamp, @duration, @requestId
| filter @duration > 1000
| sort @duration desc
| limit 20
```

**Trace a specific request by ID** (get the ID from API Gateway access logs or from the API response headers):
```
fields @timestamp, @message
| filter @message like "abc-123-your-request-id"
| sort @timestamp desc
```

**Count invocations per minute (traffic rate):**
```
fields @timestamp
| filter @message like /START RequestId/
| stats count() as invocations by bin(1m)
| sort @timestamp desc
```

**Find milestone notifications sent:**
```
fields @timestamp, @message
| filter @message like /Milestone/
| sort @timestamp desc
```

---

### Alarms

**Console path:** `CloudWatch → Alarms → All alarms`

Two alarms are configured. Both send notifications to the SNS topic, which delivers to your email.

#### Alarm: `serverless-visitor-app-lambda-invocation-spike`

| Property | Value |
|----------|-------|
| **Metric** | `AWS/Lambda` → `Invocations` → FunctionName = `serverless-visitor-app-visitor-counter` |
| **Threshold** | Sum > 1,000 over 1 period of 600 seconds (10 minutes) |
| **Equivalent rate** | ~1.7 requests/second sustained |
| **Action** | Publishes to SNS topic → email |
| **Missing data treatment** | `notBreaching` — alarm stays OK when the function is idle |

**Why 1,000 in 10 minutes?** This is roughly 10× what you'd expect from a normal personal visitor counter. A healthy organic visitor rate might be 0–5 visits per minute. 1,000 in 10 minutes suggests a script, crawler, or someone click-bombing the button. The API Gateway throttle (50 req/s) prevents it from going further, but the alarm fires early so you can investigate.

When this alarm fires:
1. Check the API Gateway Dashboard for the spike
2. Check CloudWatch Logs Insights with the "count by minute" query to see when it started
3. Check the API Gateway access logs for the source IP (`"ip"` field)
4. Consider adding a WAF rule if the traffic is abusive

#### Alarm: `serverless-visitor-app-dlq-messages`

| Property | Value |
|----------|-------|
| **Metric** | `AWS/SQS` → `ApproximateNumberOfMessagesVisible` → QueueName = DLQ |
| **Threshold** | Sum > 0 over 1 period of 300 seconds (5 minutes) |
| **Action** | Publishes to SNS topic → email |
| **Missing data treatment** | `notBreaching` — alarm stays OK when the DLQ is idle |

When this alarm fires:
1. Open the DLQ page in SQS → poll for messages → read the failing message body
2. Check SQSProcessorFunction CloudWatch Logs for the error that caused the failure
3. Fix the bug and deploy a fix
4. Use DLQ redrive to replay the failed messages

#### Alarm states

| State | Indicator | Meaning |
|-------|-----------|---------|
| **OK** | Green | Metric is within normal range |
| **In alarm** | Red | Threshold crossed; email sent to `NotificationEmail` |
| **Insufficient data** | Grey | Not enough data points yet; normal when the function has not been invoked recently |

---

### Metrics explorer

**Console path:** `CloudWatch → Metrics → All metrics`

Browse or search for any metric across all services. Useful for building custom views beyond what the individual service dashboards show.

To find this application's metrics:
- **Lambda:** Namespace `AWS/Lambda` → filter by FunctionName containing `serverless-visitor-app`
- **DynamoDB:** Namespace `AWS/DynamoDB` → filter by TableName = `serverless-visitor-app-visitors`
- **SQS:** Namespace `AWS/SQS` → filter by QueueName containing `serverless-visitor-app`
- **API Gateway:** Namespace `AWS/ApiGateway` → filter by ApiName = `serverless-visitor-app-api`

---

## Budgets

**Console path:** `Services → Billing and Cost Management → Budgets → serverless-visitor-app-monthly-budget`

The budget tracks actual and forecasted monthly AWS spend for the whole account. It is not scoped to just this stack — it covers everything billed to the account.

### Budget detail page

| Field | Value |
|-------|-------|
| **Budget amount** | $10.00 USD / month |
| **Current spend** | Shown in real time (updates several times per day) |
| **Forecasted spend** | AWS's prediction for the full month based on current burn rate |
| **Budget type** | Cost |
| **Time period** | Monthly (resets on the 1st of each month) |

### Alert thresholds

| Alert | Condition | Email sent when |
|-------|-----------|-----------------|
| 80% forecasted | Forecasted > $8.00 | AWS predicts you will exceed $8 this month |
| 100% actual | Actual > $10.00 | Real charges have already passed $10 |

The forecasted alert fires early (mid-month if spend is trending high), giving time to investigate before the actual limit is crossed.

### Cost breakdown by service

To understand what is driving spend, go to:
`Billing and Cost Management → Cost Explorer → Service`

For a low-traffic personal visitor counter, expected monthly costs are well under $1:
- **CloudFront:** ~$0.01 (data transfer out, low traffic)
- **API Gateway:** ~$0.01 per million requests
- **Lambda:** Free tier covers 1M requests/month and 400,000 GB-seconds compute
- **DynamoDB:** Free tier covers 25 GB storage and 25 WCU/RCU
- **SQS:** Free tier covers 1M requests/month
- **S3:** Negligible for three small files
- **CloudWatch Logs:** ~$0.50/GB ingested; low for this app's log volume
- **SNS:** Free tier covers 1,000 email deliveries/month

If costs exceed expectations, check CloudWatch Logs ingestion volume and Lambda invocation count first.

---

## IAM

**Console path:** `Services → IAM → Roles`

Two execution roles enforce the principle of least privilege — each Lambda function can only perform the exact AWS actions its code actually uses.

### VisitorCounterRole: `serverless-visitor-app-visitor-counter-role`

**Console path:** `IAM → Roles → serverless-visitor-app-visitor-counter-role`

#### Permissions tab

| Policy | Type | Actions granted |
|--------|------|----------------|
| `AWSLambdaBasicExecutionRole` | AWS managed | `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents` — write to CloudWatch Logs |
| `VisitorCounterInlinePolicy` | Inline | See below |

**Inline policy — DynamoDB:**
- Actions: `dynamodb:GetItem`, `dynamodb:UpdateItem`, `dynamodb:PutItem`
- Resource: `arn:aws:dynamodb:us-east-1:<account-id>:table/serverless-visitor-app-visitors`

**Inline policy — SQS:**
- Actions: `sqs:SendMessage`
- Resource: `arn:aws:sqs:us-east-1:<account-id>:serverless-visitor-app-visitor-queue`

**What is intentionally absent:**
- `dynamodb:DeleteItem`, `dynamodb:Scan`, `dynamodb:Query` — the function never deletes or scans; excluding these limits blast radius if credentials were compromised
- `sqs:ReceiveMessage`, `sqs:DeleteMessage` — this role is a producer only; it cannot consume or delete messages from the queue
- `sns:Publish` — VisitorCounterFunction never publishes to SNS directly; only SQSProcessorFunction does

#### Trust relationships tab

Shows which service can assume this role:
```json
{
  "Principal": { "Service": "lambda.amazonaws.com" },
  "Action": "sts:AssumeRole"
}
```

Only the Lambda service can use this role — no other AWS service or IAM user can assume it.

---

### SQSProcessorRole: `serverless-visitor-app-sqs-processor-role`

#### Inline policy — SQS:
- Actions: `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:GetQueueAttributes`, `sqs:ChangeMessageVisibility`
- Resource: the main queue ARN (not the DLQ — the processor has no direct access to the DLQ)

These four actions are exactly what Lambda's SQS event source mapping needs to poll, process, and acknowledge messages. `ChangeMessageVisibility` allows Lambda to extend the visibility timeout if processing takes longer than expected.

#### Inline policy — SNS:
- Actions: `sns:Publish`
- Resource: `arn:aws:sns:us-east-1:<account-id>:serverless-visitor-app-notifications`

Scoped to the exact topic ARN — the processor cannot publish to any other SNS topic in the account.

**What is intentionally absent:**
- `dynamodb:*` — the SQS processor never reads or writes DynamoDB; it receives visit counts from the SQS message body
- `sqs:SendMessage` — the processor is a consumer; it never produces messages

### Diagnosing AccessDenied errors

If a Lambda function fails with `AccessDeniedException` in CloudWatch Logs, the error message includes the exact IAM action and resource that was denied:

```
User: arn:aws:sts::<account-id>:assumed-role/serverless-visitor-app-visitor-counter-role/...
is not authorized to perform: dynamodb:DeleteItem
on resource: arn:aws:dynamodb:us-east-1:<account-id>:table/serverless-visitor-app-visitors
```

Compare the denied action against the inline policy in the role. If the action is legitimately needed (new code path added), add it to `template.yaml` under the appropriate `Policies` block and redeploy. Do not add `*` wildcards.

---

## Tracing a request end-to-end

To follow a single user request through every service:

1. **Get the request ID** — either from the API response `x-amzn-RequestId` header, or from the API Gateway access log (`requestId` field)

2. **API Gateway access log** — `CloudWatch → Log groups → /aws/apigateway/serverless-visitor-app` — search for the request ID to see method, IP, status, latency

3. **Lambda log** — `CloudWatch → Log groups → /aws/lambda/serverless-visitor-app-visitor-counter` — search for the request ID across all streams; find the `START` marker, read through the invocation, check for any `WARN` or `ERROR` lines

4. **DynamoDB** — if it was a POST, the `lastUpdated` attribute on the `VISITOR_COUNTER` item will have been updated to the request timestamp

5. **SQS** — if it was a POST and SQS succeeded, a message was briefly in the main queue; it will have been processed and deleted within seconds

6. **SQS Processor Lambda log** — `CloudWatch → Log groups → /aws/lambda/serverless-visitor-app-sqs-processor` — find the invocation that processed the message; check if a milestone notification was triggered

7. **SNS** — if visitor count hit a multiple of 100, an SNS publish occurred; check the SNS metrics for a `NumberOfMessagesPublished` data point at the request timestamp

8. **Email inbox** — if a milestone was hit and the subscription is confirmed, an email should have arrived within 1–2 minutes of the POST
