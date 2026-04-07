# Serverless Visitor Counter

A full-stack serverless web application on AWS that demonstrates the core building blocks of event-driven, cloud-native architecture: static hosting, a REST API, a NoSQL counter, async queue processing, and push notifications — with no servers to manage.

## Features

- **Global static website** served over HTTPS from CloudFront edge nodes
- **REST API** (`GET /visit`, `POST /visit`) backed by a Java 17 Lambda
- **Atomic visitor counter** persisted in DynamoDB
- **Async event queue** via SQS — visit events are processed independently of the API response
- **Milestone email alerts** via SNS every 100th visitor
- **Dead letter queue** captures any failed SQS messages for investigation
- **Least-privilege IAM** — each Lambda role has only the permissions it needs

## Architecture overview

```
Browser
  │
  ├─── HTTPS ──► CloudFront ──► S3 (HTML / JS)
  │
  └─── HTTPS ──► API Gateway
                     │
                     ▼
             VisitorCounterFunction (Java 17)
               │                  │
               ▼                  ▼
           DynamoDB          SQS Queue ──► SQSProcessorFunction
         (atomic ADD)                             │
                                                  ▼ (every 100th visitor)
                                             SNS Topic ──► Email
```

See [`docs/architecture.md`](docs/architecture.md) for the full component breakdown and [`docs/flow.md`](docs/flow.md) for step-by-step request flows.

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| AWS CLI | v2.x | `brew install awscli` |
| AWS SAM CLI | v1.100+ | `brew install aws-sam-cli` |
| Java JDK | 17 | `sdk install java 17.x.x-amzn` (sdkman) |
| Maven | 3.9+ | `brew install maven` |
| Docker | any | Required for `sam local` only |

```bash
aws configure              # set credentials + default region
aws sts get-caller-identity  # verify
```

## Quick start

```bash
# 1. Build the Lambda artifact
sam build

# 2. Deploy (first time — follow the prompts)
sam deploy --guided --parameter-overrides NotificationEmail=you@example.com

# 3. Capture outputs
API_URL=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" \
  --output text)

BUCKET=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='WebsiteBucketName'].OutputValue" \
  --output text)

DIST_ID=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='CloudFrontDistributionId'].OutputValue" \
  --output text)

# 4. Inject the API URL and upload the frontend
echo "window.API_BASE_URL = \"${API_URL}\";" > frontend/config.js
aws s3 sync frontend/ s3://${BUCKET}/
aws cloudfront create-invalidation --distribution-id ${DIST_ID} --paths "/*"
```

Then open the `WebsiteUrl` output in a browser, and confirm the SNS subscription email AWS sends you.

Full step-by-step instructions, local development, and teardown are in [`DEPLOYMENT.md`](DEPLOYMENT.md).

## Development

**Unit tests** — no Docker or AWS credentials needed; all AWS calls are mocked:

```bash
mvn test                                           # all tests
mvn test -Dtest=VisitorCounterHandlerTest          # single class
mvn test -Dtest=VisitorCounterHandlerTest#get_returnsCurrentCount  # single method
```

**Local Lambda invocation** — runs the handler in Docker against your real deployed AWS resources:

```bash
sam build
sam local invoke VisitorCounterFunction --event events/api-get.json  --env-vars local-env.json
sam local invoke VisitorCounterFunction --event events/api-post.json --env-vars local-env.json
sam local invoke SQSProcessorFunction   --event events/sqs-event.json --env-vars local-env.json
```

**Local API Gateway** — HTTP server on port 3000 backed by the Lambda container:

```bash
sam local start-api --env-vars local-env.json
curl -s http://127.0.0.1:3000/visit | python3 -m json.tool
curl -s -X POST http://127.0.0.1:3000/visit -H "Content-Type: application/json" -d '{}' | python3 -m json.tool
```

**Other:**

```bash
sam validate --lint                                # validate template.yaml
```

See [`DEPLOYMENT.md`](DEPLOYMENT.md) for how to create `local-env.json`, full curl examples, frontend local testing, and details on what each level of testing does and does not require.

## Project layout

```
template.yaml          SAM template — all AWS resources
samconfig.toml         SAM CLI deploy profiles (prod, dev)
pom.xml                Maven — builds uber-JAR via maven-shade-plugin

src/main/java/com/example/
  handlers/
    VisitorCounterHandler.java   API Gateway handler (GET + POST /visit)
    SQSProcessorHandler.java     SQS batch handler with partial failure support
  service/
    VisitorService.java          DynamoDB counter logic + SQS producer
    NotificationService.java     SNS publish wrapper

src/test/java/com/example/handlers/
  VisitorCounterHandlerTest.java
  SQSProcessorHandlerTest.java

frontend/
  index.html           Static site UI
  app.js               API fetch logic; reads window.API_BASE_URL
  config.js            Generated at deploy time — gitignored

events/                Sample JSON payloads for sam local invoke
docs/                  Architecture, decision records, flow diagrams
DEPLOYMENT.md          Detailed deployment and teardown instructions
```

## Teardown

```bash
# 1. Empty the S3 bucket (CloudFormation cannot delete a non-empty bucket)
BUCKET=$(aws cloudformation describe-stacks \
  --stack-name serverless-visitor-app \
  --query "Stacks[0].Outputs[?OutputKey=='WebsiteBucketName'].OutputValue" \
  --output text)
aws s3 rm s3://${BUCKET} --recursive

# 2. Delete the stack
aws cloudformation delete-stack --stack-name serverless-visitor-app
aws cloudformation wait stack-delete-complete --stack-name serverless-visitor-app
```

The DynamoDB table (`serverless-visitor-app-visitors`) is intentionally **not** deleted by the stack — it has `DeletionPolicy: Retain` to protect accumulated visitor data. To delete it manually:

```bash
aws dynamodb delete-table --table-name serverless-visitor-app-visitors
aws dynamodb wait table-not-exists --table-name serverless-visitor-app-visitors
```

## Re-deploying after teardown

> **Important:** Because the DynamoDB table survives stack deletion, you must delete it before creating a fresh stack. CloudFormation's early validation detects the name conflict and fails the changeset before any resources are created.

If visitor data doesn't matter (e.g. dev/test):

```bash
# Delete the retained table, then redeploy normally
aws dynamodb delete-table --table-name serverless-visitor-app-visitors
aws dynamodb wait table-not-exists --table-name serverless-visitor-app-visitors
sam build && sam deploy
```

If you want to **preserve the visitor count**, use CloudFormation resource import to bring the existing table under management of the new stack instead of deleting it. See [AWS docs on resource import](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/resource-import.html).

## Troubleshooting

**`EarlyValidation::ResourceExistenceCheck` fails at changeset creation**

The DynamoDB table already exists from a previous deployment. Delete it (see above) and redeploy. This happens every time a stack with `DeletionPolicy: Retain` resources is deleted and recreated with the same explicit resource names.

**`VisitorApiStage` fails: "CloudWatch Logs role ARN must be set"**

The `ApiGatewayAccount` resource in `template.yaml` sets this automatically. If you see this error, ensure the `ApiGatewayCloudWatchRole` and `ApiGatewayAccount` resources are present in the template and that `VisitorApi` has `DependsOn: ApiGatewayAccount`.

**Stack stuck in `REVIEW_IN_PROGRESS`**

A changeset was created but never executed (or the previous deploy was aborted). Delete the stack and start fresh:

```bash
aws cloudformation delete-stack --stack-name serverless-visitor-app
aws cloudformation wait stack-delete-complete --stack-name serverless-visitor-app
```

**SNS milestone emails not arriving**

Confirm the subscription. AWS sends a confirmation email to `NotificationEmail` when the stack is created — the subscription is inactive until you click the link. Check your spam folder, then re-subscribe:

```bash
aws sns list-subscriptions-by-topic \
  --topic-arn $(aws cloudformation describe-stacks \
    --stack-name serverless-visitor-app \
    --query "Stacks[0].Outputs[?OutputKey=='DLQUrl']" \
    --output text)
```

## Documentation

| Document | Contents |
|----------|----------|
| [`docs/architecture.md`](docs/architecture.md) | Component diagram, data model, IAM boundaries |
| [`docs/decisions.md`](docs/decisions.md) | Architecture Decision Records — why each technology was chosen |
| [`docs/flow.md`](docs/flow.md) | Step-by-step request and data flows |
| [`docs/console-guide.md`](docs/console-guide.md) | AWS Console monitoring guide — what to look at in each service |
| [`DEPLOYMENT.md`](DEPLOYMENT.md) | AWS CLI deployment, local dev, teardown |
