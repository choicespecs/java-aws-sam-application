package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SnsException;

/**
 * Thin wrapper around SNS publish.  The topic ARN is sourced from the
 * {@code SNS_TOPIC_ARN} environment variable set by CloudFormation.
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SnsClient sns;
    private final String topicArn;

    /** Production constructor — reads env var set by SAM/CloudFormation. */
    public NotificationService(SnsClient sns) {
        this(sns, System.getenv("SNS_TOPIC_ARN"));
    }

    /** Full constructor for unit testing. */
    public NotificationService(SnsClient sns, String topicArn) {
        this.sns      = sns;
        this.topicArn = topicArn;
    }

    /**
     * Publishes a message to the SNS topic.
     *
     * @param subject email subject line (max 100 chars for email protocol)
     * @param message notification body
     * @throws RuntimeException if SNS publish fails
     */
    public void sendNotification(String subject, String message) {
        // Truncate subject to SNS limit for email subscriptions
        String safeSubject = subject.length() > 100 ? subject.substring(0, 97) + "..." : subject;

        try {
            sns.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .subject(safeSubject)
                    .message(message)
                    .build());

            log.info("SNS notification published: subject=\"{}\"", safeSubject);

        } catch (SnsException e) {
            log.error("SNS Publish failed: topicArn={} errorCode={}", topicArn, e.awsErrorDetails().errorCode(), e);
            throw new RuntimeException("Failed to publish SNS notification", e);
        }
    }
}
