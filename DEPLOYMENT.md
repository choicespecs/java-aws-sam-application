# Deployment Guide

## Prerequisites

| Tool | Minimum version | Install |
|------|----------------|---------|
| AWS CLI | v2.x | `brew install awscli` |
| AWS SAM CLI | v1.100+ | `brew install aws-sam-cli` |
| Java JDK | 21 | `brew install openjdk@21` |
| Maven | 3.9+ | `brew install maven` |
| Docker | any | Required for `sam local` only |

Configure your AWS credentials:
```bash
aws configure          # or use SSO: aws configure sso
aws sts get-caller-identity   # verify
```

---

## First deployment

### 1. Build

```bash
sam build
```

Runs `mvn package` (produces the uber-JAR) and stages artifacts under `.aws-sam/build/`.

### 2. Deploy (guided — sets up S3 bucket and saves config)

```bash
sam deploy --guided \
  --parameter-overrides NotificationEmail=you@example.com Environment=prod
```

Answer the prompts. SAM saves your choices to `samconfig.toml` for future runs.

After the stack creates, note the outputs:

```
Key             WebsiteUrl
Value           https://d1234abcd.cloudfront.net

Key             ApiUrl
Value           https://abc123.execute-api.us-east-1.amazonaws.com/prod

Key             WebsiteBucketName
Value           serverless-visitor-app-website-<account-id>

Key             CloudFrontDistributionId
Value           E1ABCDEF2GHIJK
```

### 3. Inject the API URL into the frontend

```bash
API_URL=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" \
  --output text)

echo "window.API_BASE_URL = \"${API_URL}\";" > frontend/config.js
```

### 4. Upload the frontend to S3

```bash
BUCKET=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='WebsiteBucketName'].OutputValue" \
  --output text)

aws s3 sync frontend/ s3://${BUCKET}/ \
  --delete \
  --cache-control "max-age=300"
```

### 5. Invalidate CloudFront cache

```bash
DIST_ID=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='CloudFrontDistributionId'].OutputValue" \
  --output text)

aws cloudfront create-invalidation \
  --distribution-id ${DIST_ID} \
  --paths "/*"
```

### 6. Confirm the SNS subscription

Check your inbox for an email from AWS Notifications and click **Confirm subscription**.
Milestone alerts will not arrive until this step is complete.

---

## Subsequent deployments

```bash
# Lambda / infrastructure changes
sam build && sam deploy

# Frontend-only change (skip sam build/deploy)
echo "window.API_BASE_URL = \"${API_URL}\";" > frontend/config.js
aws s3 sync frontend/ s3://${BUCKET}/ --delete
aws cloudfront create-invalidation --distribution-id ${DIST_ID} --paths "/*"
```

---

## Local development

There are two levels of local testing, with different requirements:

| Level | What runs locally | Needs Docker | Needs AWS credentials | Hits real AWS |
|-------|------------------|-------------|----------------------|---------------|
| Unit tests | JVM only | No | No | No |
| `sam local` (Lambda / API) | Lambda in Docker | Yes | Yes | Yes (DynamoDB, SQS, SNS) |

---

### Unit tests (no Docker, no AWS)

The unit tests mock all AWS SDK calls with Mockito — they run entirely in the JVM and need no credentials or network access.

```bash
# Run all tests
mvn test

# Single test class
mvn test -Dtest=VisitorCounterHandlerTest
mvn test -Dtest=SQSProcessorHandlerTest

# Single test method
mvn test -Dtest=VisitorCounterHandlerTest#get_returnsCurrentCount
```

What the tests cover:
- `VisitorCounterHandlerTest` — GET / POST / OPTIONS routing, CORS headers, 500 on service failure
- `SQSProcessorHandlerTest` — milestone detection (every 100th visitor), partial batch failure reporting, malformed JSON handling

---

### Local Lambda invocation (requires Docker + deployed stack)

`sam local invoke` runs the Lambda handler inside a Docker container on your machine, but it still calls real AWS services (DynamoDB, SQS, SNS) using your local credentials. The stack must be deployed first so those resources exist.

#### 1. Create `local-env.json`

Generate it from your deployed stack outputs so the values are always correct:

```bash
STACK=serverless-visitor-app

TABLE=$(aws cloudformation describe-stacks --stack-name $STACK \
  --query "Stacks[0].Outputs[?OutputKey=='WebsiteBucketName']" \
  --output text 2>/dev/null || echo "serverless-visitor-app-visitors")

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

> `local-env.json` is gitignored — never commit it; it contains your live resource ARNs.

#### 2. Build

```bash
sam build
```

Lambda code is served from `.aws-sam/build/`, not `target/`. Always rebuild after code changes.

#### 3. Invoke individual functions

```bash
# GET /visit — returns current visitor count from DynamoDB
sam local invoke VisitorCounterFunction \
  --event events/api-get.json \
  --env-vars local-env.json

# POST /visit — atomically increments DynamoDB counter and enqueues SQS message
sam local invoke VisitorCounterFunction \
  --event events/api-post.json \
  --env-vars local-env.json

# SQS processor — processes two visit events; count=100 triggers an SNS email
sam local invoke SQSProcessorFunction \
  --event events/sqs-event.json \
  --env-vars local-env.json
```

Expected output for a successful GET:
```json
{"statusCode": 200, "body": "{\"visitorCount\":0}"}
```

Expected output for a successful POST:
```json
{"statusCode": 200, "body": "{\"visitorCount\":1,\"message\":\"Visit recorded successfully\"}"}
```

> On first run, Docker pulls the AWS Lambda Java 17 image (~1 GB). Subsequent runs reuse the cached image and are faster.

---

### Local API Gateway (requires Docker + deployed stack)

`sam local start-api` starts a local HTTP server on port 3000 that emulates API Gateway and invokes the Lambda container on each request.

```bash
sam local start-api --env-vars local-env.json
```

Then in another terminal:

```bash
# Read visitor count
curl -s http://127.0.0.1:3000/visit | python3 -m json.tool

# Record a visit
curl -s -X POST http://127.0.0.1:3000/visit \
  -H "Content-Type: application/json" \
  -d '{}' | python3 -m json.tool

# CORS pre-flight check
curl -s -X OPTIONS http://127.0.0.1:3000/visit \
  -H "Origin: http://localhost" \
  -H "Access-Control-Request-Method: POST" -v 2>&1 | grep "< Access-Control"
```

---

### Frontend (no AWS required)

The frontend is plain HTML/JS and can be opened directly in a browser, pointed at either the live API or the local API Gateway.

**Against the live deployed API:**

```bash
API_URL=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" \
  --output text)

echo "window.API_BASE_URL = \"${API_URL}\";" > frontend/config.js
open frontend/index.html        # macOS
```

**Against the local API Gateway** (start `sam local start-api` first):

```bash
echo 'window.API_BASE_URL = "http://127.0.0.1:3000";' > frontend/config.js
open frontend/index.html        # macOS
```

> Browsers enforce CORS on `file://` origins. If you see CORS errors, serve the frontend over HTTP instead:
> ```bash
> python3 -m http.server 8080 --directory frontend/
> # then open http://localhost:8080
> ```

---

## Validate the template

```bash
sam validate --lint
```

---

## Tear down

```bash
# 1. Empty the S3 bucket (CloudFormation cannot delete non-empty buckets)
aws s3 rm s3://${BUCKET} --recursive

# 2. Delete the stack (DynamoDB table is retained — see DeletionPolicy: Retain)
aws cloudformation delete-stack --stack-name serverless-visitor-app
aws cloudformation wait stack-delete-complete --stack-name serverless-visitor-app
```

The DynamoDB table is **intentionally not deleted** by the stack. To delete it manually:

```bash
aws dynamodb delete-table --table-name serverless-visitor-app-visitors
aws dynamodb wait table-not-exists --table-name serverless-visitor-app-visitors
```

## Re-deploying after teardown

> **Warning:** If you deleted the stack but left the DynamoDB table intact (the default), you must delete the table before creating a new stack with the same name. CloudFormation's `AWS::EarlyValidation::ResourceExistenceCheck` hook detects the name conflict and fails the changeset immediately — before any resources are created.

**Option A — discard data (dev/test):**

```bash
aws dynamodb delete-table --table-name serverless-visitor-app-visitors
aws dynamodb wait table-not-exists --table-name serverless-visitor-app-visitors
sam build && sam deploy
```

**Option B — preserve data (production):**

Use CloudFormation resource import to bring the existing table under management of the new stack. This avoids deleting the table or losing the visitor count.

1. Deploy the stack with `VisitorTable` temporarily removed from `template.yaml`
2. Run a CloudFormation IMPORT changeset to adopt the existing table:
   ```bash
   aws cloudformation create-change-set \
     --stack-name serverless-visitor-app \
     --change-set-name import-visitor-table \
     --change-set-type IMPORT \
     --resources-to-import '[{"ResourceType":"AWS::DynamoDB::Table","LogicalResourceId":"VisitorTable","ResourceIdentifier":{"TableName":"serverless-visitor-app-visitors"}}]' \
     --template-body file://.aws-sam/build/template.yaml \
     --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM
   aws cloudformation execute-change-set \
     --change-set-name import-visitor-table \
     --stack-name serverless-visitor-app
   ```

---

## Dev environment

Deploy a separate stack for development (uses `samconfig.toml` `[dev]` profile):

```bash
sam build && sam deploy --config-env dev \
  --parameter-overrides NotificationEmail=dev@example.com Environment=dev
```
