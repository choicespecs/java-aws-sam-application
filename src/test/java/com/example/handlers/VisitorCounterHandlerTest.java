package com.example.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.service.VisitorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorCounterHandlerTest {

    @Mock private VisitorService visitorService;
    @Mock private Context context;

    private VisitorCounterHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(context.getAwsRequestId()).thenReturn("test-request-id");
        handler = new VisitorCounterHandler(visitorService);
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    @Test
    void get_returnsCurrentCount() throws Exception {
        when(visitorService.getVisitorCount()).thenReturn(42L);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request("GET"), context);

        assertEquals(200, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals(42, body.get("visitorCount").asLong());
    }

    @Test
    void get_zeroCount_returnsZero() throws Exception {
        when(visitorService.getVisitorCount()).thenReturn(0L);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request("GET"), context);

        assertEquals(200, response.getStatusCode());
        assertEquals(0, mapper.readTree(response.getBody()).get("visitorCount").asLong());
    }

    @Test
    void get_serviceThrows_returns500() {
        when(visitorService.getVisitorCount()).thenThrow(new RuntimeException("DynamoDB unreachable"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(request("GET"), context);

        assertEquals(500, response.getStatusCode());
        verifyBodyContainsError(response);
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @Test
    void post_incrementsAndReturnsNewCount() throws Exception {
        when(visitorService.incrementAndQueueVisit(anyString())).thenReturn(43L);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request("POST"), context);

        assertEquals(200, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals(43, body.get("visitorCount").asLong());
        assertNotNull(body.get("message"));
        verify(visitorService).incrementAndQueueVisit(anyString());
    }

    @Test
    void post_withSourceIp_passesIpToService() {
        when(visitorService.incrementAndQueueVisit("10.0.0.1")).thenReturn(1L);

        APIGatewayProxyRequestEvent req = request("POST");
        APIGatewayProxyRequestEvent.ProxyRequestContext ctx =
                new APIGatewayProxyRequestEvent.ProxyRequestContext();
        APIGatewayProxyRequestEvent.RequestIdentity identity =
                new APIGatewayProxyRequestEvent.RequestIdentity();
        identity.setSourceIp("10.0.0.1");
        ctx.setIdentity(identity);
        req.setRequestContext(ctx);

        handler.handleRequest(req, context);

        verify(visitorService).incrementAndQueueVisit("10.0.0.1");
    }

    @Test
    void post_serviceThrows_returns500() {
        when(visitorService.incrementAndQueueVisit(anyString()))
                .thenThrow(new RuntimeException("DynamoDB error"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(request("POST"), context);

        assertEquals(500, response.getStatusCode());
        verifyBodyContainsError(response);
    }

    // ── OPTIONS ───────────────────────────────────────────────────────────────

    @Test
    void options_returns200WithCorsHeaders() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(request("OPTIONS"), context);

        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getHeaders().get("Access-Control-Allow-Origin"));
        assertNotNull(response.getHeaders().get("Access-Control-Allow-Methods"));
        verifyNoInteractions(visitorService);
    }

    // ── Method not allowed ────────────────────────────────────────────────────

    @Test
    void delete_returns405() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(request("DELETE"), context);
        assertEquals(405, response.getStatusCode());
        verifyBodyContainsError(response);
    }

    @Test
    void put_returns405() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(request("PUT"), context);
        assertEquals(405, response.getStatusCode());
    }

    // ── CORS headers present on all responses ─────────────────────────────────

    @Test
    void allResponses_hasCorsOriginHeader() {
        when(visitorService.getVisitorCount()).thenReturn(0L);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request("GET"), context);

        assertEquals("*", response.getHeaders().get("Access-Control-Allow-Origin"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private APIGatewayProxyRequestEvent request(String method) {
        return new APIGatewayProxyRequestEvent().withHttpMethod(method);
    }

    private void verifyBodyContainsError(APIGatewayProxyResponseEvent response) {
        assertTrue(response.getBody().contains("error"),
                "Expected 'error' in response body: " + response.getBody());
    }
}
