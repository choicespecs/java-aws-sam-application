package com.example.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.example.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sns.SnsClient;

import java.util.ArrayList;
import java.util.List;

/**
 * SQS-triggered processor.  Reads visitor events from the queue, then sends an
 * SNS milestone notification every {@value #MILESTONE_INTERVAL} visitors.
 *
 * <p>Uses <em>ReportBatchItemFailures</em> so only failed messages are retried —
 * successfully processed messages in the same batch are not re-delivered.
 *
 * <p>Entry point: {@code com.example.handlers.SQSProcessorHandler::handleRequest}
 */
public class SQSProcessorHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger log = LoggerFactory.getLogger(SQSProcessorHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Send a notification every N visitors. */
    static final long MILESTONE_INTERVAL = 100;

    private static final SnsClient SNS = SnsClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    private final NotificationService notificationService;

    /** Production constructor. */
    public SQSProcessorHandler() {
        this.notificationService = new NotificationService(SNS);
    }

    /** Test constructor — inject mock service. */
    public SQSProcessorHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSEvent.SQSMessage> records = event.getRecords();
        log.info("Processing SQS batch: {} messages  requestId={}", records.size(),
                context.getAwsRequestId());

        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : records) {
            try {
                processMessage(message);
            } catch (Exception e) {
                log.error("Failed to process messageId={}: {}", message.getMessageId(), e.getMessage(), e);
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(message.getMessageId())
                        .build());
            }
        }

        int successCount = records.size() - failures.size();
        log.info("Batch complete: {} succeeded, {} failed", successCount, failures.size());
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void processMessage(SQSEvent.SQSMessage message) throws Exception {
        JsonNode payload = MAPPER.readTree(message.getBody());

        // Validate required fields
        requireField(payload, "visitorCount");
        requireField(payload, "visitorId");
        requireField(payload, "timestamp");

        long visitorCount = payload.get("visitorCount").asLong();
        String visitorId  = payload.get("visitorId").asText();
        String timestamp  = payload.get("timestamp").asText();

        log.info("Visit event: visitorId={} count={} ts={}", visitorId, visitorCount, timestamp);

        if (isMilestone(visitorCount)) {
            sendMilestoneNotification(visitorCount, visitorId, timestamp);
        }
    }

    private boolean isMilestone(long count) {
        return count > 0 && count % MILESTONE_INTERVAL == 0;
    }

    private void sendMilestoneNotification(long count, String visitorId, String timestamp) {
        String subject = String.format("🎉 Milestone reached: %,d visitors!", count);
        String body = String.format(
                "Your serverless website has reached %,d total visitors!%n%n"
                        + "Latest visitor : %s%n"
                        + "Recorded at    : %s%n%n"
                        + "Next milestone : %,d visitors",
                count, visitorId, timestamp, count + MILESTONE_INTERVAL
        );
        notificationService.sendNotification(subject, body);
        log.info("Milestone notification sent for count={}", count);
    }

    private void requireField(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
    }
}
