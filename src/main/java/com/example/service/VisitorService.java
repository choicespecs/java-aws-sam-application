package com.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Instant;
import java.util.Map;

/**
 * Business logic for visitor counting (DynamoDB) and async event queuing (SQS).
 *
 * <p>The DynamoDB item uses a single-record design:
 * <pre>
 *   pk           = "VISITOR_COUNTER"  (String, partition key)
 *   count        = N                  (Number, atomic counter)
 *   lastUpdated  = ISO-8601 string
 * </pre>
 */
public class VisitorService {

    private static final Logger log = LoggerFactory.getLogger(VisitorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Partition-key value of the single counter record. */
    static final String COUNTER_PK = "VISITOR_COUNTER";

    private final DynamoDbClient dynamoDb;
    private final SqsClient sqs;
    private final String tableName;
    private final String queueUrl;

    /** Production constructor — reads env vars set by SAM/CloudFormation. */
    public VisitorService(DynamoDbClient dynamoDb, SqsClient sqs) {
        this(dynamoDb, sqs,
                System.getenv("DYNAMODB_TABLE"),
                System.getenv("SQS_QUEUE_URL"));
    }

    /** Full constructor for unit testing. */
    public VisitorService(DynamoDbClient dynamoDb, SqsClient sqs,
                          String tableName, String queueUrl) {
        this.dynamoDb  = dynamoDb;
        this.sqs       = sqs;
        this.tableName = tableName;
        this.queueUrl  = queueUrl;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current visitor count, or 0 if the counter record does not
     * exist yet (fresh deployment).
     *
     * @throws RuntimeException if DynamoDB is unreachable
     */
    public long getVisitorCount() {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("pk", AttributeValue.fromS(COUNTER_PK)))
                    .projectionExpression("#c")
                    .expressionAttributeNames(Map.of("#c", "count"))
                    .build());

            if (response.hasItem() && response.item().containsKey("count")) {
                return Long.parseLong(response.item().get("count").n());
            }
            return 0L;

        } catch (DynamoDbException e) {
            log.error("DynamoDB GetItem failed", e);
            throw new RuntimeException("Failed to retrieve visitor count", e);
        }
    }

    /**
     * Atomically increments the counter, then enqueues a visit event for async
     * processing.  SQS failure is non-fatal (logged as a warning) so the HTTP
     * response is not affected.
     *
     * @param visitorId caller identifier (IP or generated token)
     * @return new counter value after increment
     * @throws RuntimeException if the DynamoDB update fails
     */
    public long incrementAndQueueVisit(String visitorId) {
        long newCount = atomicIncrement();
        enqueueVisitEvent(visitorId, newCount);
        return newCount;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private long atomicIncrement() {
        try {
            UpdateItemResponse response = dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("pk", AttributeValue.fromS(COUNTER_PK)))
                    .updateExpression(
                            "ADD #count :inc SET #lastUpdated = :ts")
                    .expressionAttributeNames(Map.of(
                            "#count",       "count",
                            "#lastUpdated", "lastUpdated"))
                    .expressionAttributeValues(Map.of(
                            ":inc", AttributeValue.fromN("1"),
                            ":ts",  AttributeValue.fromS(Instant.now().toString())))
                    .returnValues(ReturnValue.UPDATED_NEW)
                    .build());

            long newCount = Long.parseLong(response.attributes().get("count").n());
            log.info("Counter incremented to {}", newCount);
            return newCount;

        } catch (DynamoDbException e) {
            log.error("DynamoDB UpdateItem failed", e);
            throw new RuntimeException("Failed to increment visitor count", e);
        }
    }

    private void enqueueVisitEvent(String visitorId, long visitorCount) {
        try {
            String messageBody = MAPPER.writeValueAsString(Map.of(
                    "visitorId",    visitorId,
                    "visitorCount", visitorCount,
                    "timestamp",    Instant.now().toString()
            ));

            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build());

            log.info("Visit event queued: visitorId={} count={}", visitorId, visitorCount);

        } catch (SqsException e) {
            // Non-fatal: the counter has already been incremented; don't fail the request.
            log.warn("SQS SendMessage failed (non-fatal): {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to serialise SQS message (non-fatal)", e);
        }
    }
}
