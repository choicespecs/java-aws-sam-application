package com.example.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.example.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SQSProcessorHandlerTest {

    @Mock private NotificationService notificationService;
    @Mock private Context context;

    private SQSProcessorHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(context.getAwsRequestId()).thenReturn("test-request-id");
        handler = new SQSProcessorHandler(notificationService);
    }

    // ── Normal processing ─────────────────────────────────────────────────────

    @Test
    void nonMilestoneVisit_noNotificationSent() {
        SQSEvent event = singleMessage("msg-1", 42L, "user-A");

        SQSBatchResponse response = handler.handleRequest(event, context);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(notificationService, never()).sendNotification(anyString(), anyString());
    }

    @Test
    void firstVisit_count1_noNotification() {
        SQSBatchResponse response = handler.handleRequest(
                singleMessage("msg-1", 1L, "first-visitor"), context);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verifyNoInteractions(notificationService);
    }

    // ── Milestone detection ───────────────────────────────────────────────────

    @Test
    void exactMilestone_count100_notificationSent() {
        SQSBatchResponse response = handler.handleRequest(
                singleMessage("msg-1", 100L, "visitor-100"), context);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(notificationService).sendNotification(
                contains("100"),
                contains("100")
        );
    }

    @Test
    void exactMilestone_count200_notificationSent() {
        SQSBatchResponse response = handler.handleRequest(
                singleMessage("msg-1", 200L, "visitor-200"), context);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(notificationService).sendNotification(contains("200"), anyString());
    }

    @Test
    void nearMilestone_count99_noNotification() {
        SQSBatchResponse response = handler.handleRequest(
                singleMessage("msg-1", 99L, "visitor-99"), context);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verifyNoInteractions(notificationService);
    }

    @Test
    void pastMilestone_count101_noNotification() {
        SQSBatchResponse response = handler.handleRequest(
                singleMessage("msg-1", 101L, "visitor-101"), context);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verifyNoInteractions(notificationService);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void malformedJson_returnsItemFailure() {
        SQSEvent event = eventWithMessages(
                rawMessage("bad-msg", "this is not json"));

        SQSBatchResponse response = handler.handleRequest(event, context);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("bad-msg", response.getBatchItemFailures().get(0).getItemIdentifier());
        verifyNoInteractions(notificationService);
    }

    @Test
    void missingVisitorCountField_returnsItemFailure() {
        SQSEvent event = eventWithMessages(
                rawMessage("msg-1", "{\"visitorId\":\"user\",\"timestamp\":\"2024-01-01T00:00:00Z\"}"));

        SQSBatchResponse response = handler.handleRequest(event, context);

        assertEquals(1, response.getBatchItemFailures().size());
    }

    @Test
    void notificationServiceThrows_returnsItemFailure() {
        doThrow(new RuntimeException("SNS unavailable"))
                .when(notificationService).sendNotification(anyString(), anyString());

        SQSBatchResponse response = handler.handleRequest(
                singleMessage("msg-1", 100L, "visitor-100"), context);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("msg-1", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // ── Partial batch failures ────────────────────────────────────────────────

    @Test
    void mixedBatch_onlyFailedMessagesReported() {
        SQSEvent event = eventWithMessages(
                message("good-1", 1L, "user-1"),
                rawMessage("bad-msg", "invalid-json"),
                message("good-2", 2L, "user-2")
        );

        SQSBatchResponse response = handler.handleRequest(event, context);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("bad-msg", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void allMessagesFail_allReportedAsFailures() {
        SQSEvent event = eventWithMessages(
                rawMessage("bad-1", "{}"),   // missing required fields
                rawMessage("bad-2", "[]")    // wrong type
        );

        SQSBatchResponse response = handler.handleRequest(event, context);

        assertEquals(2, response.getBatchItemFailures().size());
    }

    @Test
    void emptyBatch_returnsEmptyFailures() {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of());

        SQSBatchResponse response = handler.handleRequest(event, context);

        assertTrue(response.getBatchItemFailures().isEmpty());
    }

    // ── MILESTONE_INTERVAL constant ───────────────────────────────────────────

    @Test
    void milestoneInterval_isHundred() {
        assertEquals(100L, SQSProcessorHandler.MILESTONE_INTERVAL);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SQSEvent singleMessage(String id, long count, String visitorId) {
        return eventWithMessages(message(id, count, visitorId));
    }

    private SQSEvent eventWithMessages(SQSEvent.SQSMessage... messages) {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(messages));
        return event;
    }

    private SQSEvent.SQSMessage message(String id, long count, String visitorId) {
        String body = String.format(
                "{\"visitorId\":\"%s\",\"visitorCount\":%d,\"timestamp\":\"2024-01-01T00:00:00Z\"}",
                visitorId, count);
        return rawMessage(id, body);
    }

    private SQSEvent.SQSMessage rawMessage(String id, String body) {
        SQSEvent.SQSMessage msg = new SQSEvent.SQSMessage();
        msg.setMessageId(id);
        msg.setBody(body);
        return msg;
    }
}
