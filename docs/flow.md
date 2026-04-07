# Request and Data Flows

## 1. Page load

A user opens the website URL (the CloudFront domain).

```
Browser                  CloudFront            S3
   │                         │                  │
   │── GET / ───────────────►│                  │
   │                         │── GetObject ────►│
   │                         │   (index.html)   │
   │                         │◄─ 200 ───────────│
   │◄─ 200 (index.html) ─────│                  │
   │                         │                  │
   │── GET /app.js ──────────►│                  │
   │                         │  (cache hit)      │
   │◄─ 200 (app.js) ─────────│                  │
   │                         │                  │
   │── GET /config.js ───────►│                  │
   │◄─ 200 (config.js) ──────│                  │
   │   window.API_BASE_URL    │                  │
   │   = "https://..."        │                  │
```

**Notes**
- CloudFront serves from its edge cache after the first request; S3 is not contacted again until the cache TTL expires or an invalidation is issued.
- `config.js` sets `window.API_BASE_URL` — the API endpoint. `app.js` reads this at runtime and immediately fires `GET /visit` to load the counter (flow 2 below).
- If any path other than `/` is requested directly (e.g., a bookmarked deep link), CloudFront's custom error response rewrites the S3 `403` to `200 index.html`, and the JavaScript handles routing.

---

## 2. Fetch current visitor count (GET /visit)

Runs automatically when `app.js` loads. Updates the counter display.

```
Browser         API Gateway        VisitorCounterFunction        DynamoDB
   │                 │                       │                      │
   │── GET /visit ──►│                       │                      │
   │                 │── invoke ────────────►│                      │
   │                 │                       │── GetItem ──────────►│
   │                 │                       │   pk="VISITOR_       │
   │                 │                       │   COUNTER"           │
   │                 │                       │◄─ {count: 42} ───────│
   │                 │◄─ 200 ────────────────│                      │
   │                 │   {visitorCount: 42}  │                      │
   │◄─ 200 ──────────│                       │                      │
   │  {visitorCount: 42}                     │                      │
   │                                         │                      │
   [counter display updated to "42"]
```

**Error path**
If DynamoDB is unreachable, `VisitorService.getVisitorCount()` throws a `RuntimeException`. The handler catches it, logs the stack trace, and returns `HTTP 500 {"error": "Internal server error"}`. The frontend displays "Could not load visitor count" and leaves the button enabled so the user can retry.

---

## 3. Register a visit (POST /visit)

Triggered when the user clicks "Register My Visit".

```
Browser      API Gateway   VisitorCounterFunction    DynamoDB       SQS Queue
   │              │                  │                   │               │
   │─ POST /visit►│                  │                   │               │
   │              │─── invoke ──────►│                   │               │
   │              │                  │                   │               │
   │              │                  │── UpdateItem ────►│               │
   │              │                  │   ADD count 1     │               │
   │              │                  │   SET lastUpdated │               │
   │              │                  │◄─ {count: 43} ────│               │
   │              │                  │                   │               │
   │              │                  │── SendMessage ────────────────────►│
   │              │                  │   {visitorId,     │               │
   │              │                  │    visitorCount:43│               │
   │              │                  │    timestamp}     │               │
   │              │                  │                   │               │
   │              │◄─ 200 ───────────│                   │               │
   │              │  {visitorCount:43│                   │               │
   │              │   message:"..."}  │                   │               │
   │◄─ 200 ───────│                  │                   │               │
   │  {visitorCount: 43}             │                   │               │
   │                                 │                   │               │
   [counter animates to "43"]
```

**SQS failure is non-fatal**
If `SendMessage` fails (network blip, SQS outage), `VisitorService.enqueueVisitEvent()` catches the `SqsException`, logs a warning, and returns normally. The DynamoDB increment has already committed — the counter is correct. The visit event is simply not queued, meaning no milestone notification will fire for that specific visit.

```
   │── UpdateItem ─►│  ✓ count = 43
   │                │
   │── SendMessage ►│  ✗ SqsException
   │                │    logged as WARN, not re-thrown
   │◄──────────────  counter response still returned
```

**Error path (DynamoDB failure)**
If `UpdateItem` fails, the function throws, the handler catches it, and returns `HTTP 500`. No SQS message is sent (the code never reaches that line). The counter is not incremented.

---

## 4. Async SQS processing

Runs independently of the API response, triggered automatically by Lambda's SQS event source mapping.

```
SQS Queue          Lambda ESM        SQSProcessorFunction       SNS Topic        Email
    │                  │                      │                      │               │
    │  (5s batch       │                      │                      │               │
    │   window or      │                      │                      │               │
    │   10 messages)   │                      │                      │               │
    │──────────────────►│                      │                      │               │
    │                  │──── invoke ──────────►│                      │               │
    │                  │    [{msg1..msg10}]    │                      │               │
    │                  │                       │── processMessage()   │               │
    │                  │                       │   for each msg       │               │
    │                  │                       │                      │               │
    │                  │                       │── if count % 100 == 0│               │
    │                  │                       │── Publish ──────────►│               │
    │                  │                       │   subject, body      │               │
    │                  │                       │◄─ messageId ─────────│               │
    │                  │                       │                      │──── deliver ──►│
    │                  │◄─ SQSBatchResponse ───│                      │               │
    │                  │   {batchItemFailures: │                      │               │
    │                  │    []}                │                      │               │
    │  delete msgs     │                       │                      │               │
    │◄─────────────────│                       │                      │               │
```

**Partial batch failure path**
If one message in the batch fails (e.g., malformed JSON, SNS outage), only that message ID is included in `batchItemFailures`. The other messages are deleted from the queue normally.

```
    batch: [msg1, msg2(bad), msg3]
                │
    msg1 processed ✓
    msg2 throws  ✗ ── added to batchItemFailures
    msg3 processed ✓
                │
    SQSBatchResponse.batchItemFailures = ["msg2-id"]
                │
    Lambda ESM: delete msg1, delete msg3
                make msg2 visible again after VisibilityTimeout
```

After 3 failed attempts (`maxReceiveCount: 3`), msg2 is moved to the DLQ.

---

## 5. Milestone notification

Milestone detection is inside `SQSProcessorFunction`. Every visit event has its `visitorCount` checked.

```
SQSProcessorHandler.processMessage()
        │
        ├── parse payload: {visitorId, visitorCount, timestamp}
        │
        ├── visitorCount % 100 == 0 ?
        │       │
        │      YES ──► NotificationService.sendNotification()
        │               │
        │               ├── truncate subject to 100 chars (SNS email limit)
        │               └── SnsClient.publish(topicArn, subject, body)
        │                       │
        │                      ✓ ──► SNS delivers to email subscriber
        │
        └──── NO ──► (nothing, message is still deleted from queue)
```

**Milestone body format**
```
Subject : 🎉 Milestone reached: 100 visitors!
Body    : Your serverless website has reached 100 total visitors!

          Latest visitor : 203.0.113.42
          Recorded at    : 2024-01-15T10:30:00Z

          Next milestone : 200 visitors
```

---

## 6. CORS pre-flight (OPTIONS /visit)

Browsers send an OPTIONS request before any cross-origin `fetch()` with custom headers. Both API Gateway and the Lambda handler must respond correctly.

```
Browser           API Gateway        VisitorCounterFunction
   │                   │                      │
   │── OPTIONS /visit ─►│                      │
   │   Origin: https://d1234.cloudfront.net    │
   │   Access-Control-Request-Method: POST     │
   │                   │──── invoke ──────────►│
   │                   │                       │── returns 200 with:
   │                   │◄──────────────────────│   Access-Control-Allow-Origin: *
   │                   │                       │   Access-Control-Allow-Methods: GET,POST,OPTIONS
   │                   │                       │   Access-Control-Allow-Headers: ...
   │◄── 200 ───────────│                       │
   │   Access-Control headers                  │
   │                   │                       │
   │── POST /visit ────►│                       │  (actual request proceeds)
```

CORS headers are returned on **every** response from `VisitorCounterHandler` (success, error, and OPTIONS), not just on pre-flights. This ensures the browser can read error responses too — without the header, a `500` response body is opaque to JavaScript.
