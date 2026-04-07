package com.example.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.service.VisitorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;

/**
 * API Gateway handler for GET /visit (fetch count) and POST /visit (record visit).
 *
 * <p>Entry point: {@code com.example.handlers.VisitorCounterHandler::handleRequest}
 */
public class VisitorCounterHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger log = LoggerFactory.getLogger(VisitorCounterHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Clients are static so they survive Lambda container reuse (connection pooling).
    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    private static final SqsClient SQS = SqsClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    private final VisitorService visitorService;

    /** Production constructor — uses real AWS clients. */
    public VisitorCounterHandler() {
        this.visitorService = new VisitorService(DYNAMO, SQS);
    }

    /** Test constructor — inject mock service. */
    public VisitorCounterHandler(VisitorService visitorService) {
        this.visitorService = visitorService;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent request, Context context) {

        String method = request.getHttpMethod();
        log.info("Received {} /visit  requestId={}", method, context.getAwsRequestId());

        try {
            return switch (method.toUpperCase()) {
                case "GET"     -> handleGet();
                case "POST"    -> handlePost(request);
                case "OPTIONS" -> handleOptions();
                default        -> error(405, "Method not allowed: " + method);
            };
        } catch (Exception e) {
            log.error("Unhandled exception in VisitorCounterHandler", e);
            return error(500, "Internal server error");
        }
    }

    // ── Request handlers ─────────────────────────────────────────────────────

    private APIGatewayProxyResponseEvent handleGet() {
        long count = visitorService.getVisitorCount();
        log.info("Current visitor count: {}", count);
        return ok(Map.of("visitorCount", count));
    }

    private APIGatewayProxyResponseEvent handlePost(APIGatewayProxyRequestEvent request) {
        String visitorId = resolveVisitorId(request);
        long newCount = visitorService.incrementAndQueueVisit(visitorId);
        log.info("Visit recorded: visitorId={} newCount={}", visitorId, newCount);
        return ok(Map.of(
                "visitorCount", newCount,
                "message", "Visit recorded successfully"
        ));
    }

    /** CORS pre-flight — API Gateway handles this but Lambda must respond too. */
    private APIGatewayProxyResponseEvent handleOptions() {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(corsHeaders())
                .withBody("");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts a visitor identifier from the request context (source IP).
     * Falls back to a timestamp-based token so the counter always increments.
     */
    private String resolveVisitorId(APIGatewayProxyRequestEvent request) {
        try {
            var identity = request.getRequestContext().getIdentity();
            if (identity != null && identity.getSourceIp() != null) {
                return identity.getSourceIp();
            }
        } catch (NullPointerException ignored) {
            // Request context absent (e.g., local testing)
        }
        return "anon-" + System.currentTimeMillis();
    }

    private APIGatewayProxyResponseEvent ok(Object body) {
        return buildResponse(200, body);
    }

    private APIGatewayProxyResponseEvent error(int status, String message) {
        return buildResponse(status, Map.of("error", message));
    }

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(corsHeaders())
                    .withBody(MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            log.error("Response serialisation failed", e);
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(corsHeaders())
                    .withBody("{\"error\":\"Response serialisation failed\"}");
        }
    }

    private Map<String, String> corsHeaders() {
        return Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Allow-Methods", "GET,POST,OPTIONS",
                "Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key"
        );
    }
}
