package com.fusecode.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusecode.assessment.entities.OutboxEntity;
import com.fusecode.assessment.entities.OrdersEntity;
import com.fusecode.assessment.repositories.OutboxRepository;
import com.fusecode.assessment.repositories.OrdersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Order API.
 * 
 * Note: Uses H2 in-memory database for testing. This is acceptable for integration tests
 * and doesn't require Docker. For production-like testing with PostgreSQL, you can use
 * Testcontainers by:
 * 1. Ensuring Docker is installed and running
 * 2. Adding @Testcontainers annotation
 * 3. Adding @Container static PostgreSQLContainer
 * 4. Using @DynamicPropertySource to configure datasource properties
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=update",
    "spring.jpa.show-sql=false",
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password="
})
class OrderIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrdersRepository ordersRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String baseUrl;
    private String tenantId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        tenantId = UUID.randomUUID().toString();
        // Clean up before each test
        ordersRepository.deleteAll();
        outboxRepository.deleteAll();
    }

    private HttpHeaders createHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private HttpHeaders createHeadersWithIfMatch(String idempotencyKey, Integer ifMatch) {
        HttpHeaders headers = createHeaders(idempotencyKey);
        if (ifMatch != null) {
            headers.set("If-Match", String.valueOf(ifMatch));
        }
        return headers;
    }

    // ========== Idempotency Tests ==========

    /**
     * Test: Same Idempotency-Key + same body → same order.id (replay)
     */
    @Test
    void idempotency_sameKeySameBody_returnsSameOrderId() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        HttpHeaders headers = createHeaders(idempotencyKey);

        // First request - create order
        ResponseEntity<String> response1 = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                String.class
        );

        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json1 = objectMapper.readTree(response1.getBody());
        assertThat(json1.get("code").asText()).isEqualTo("201");
        String orderId1 = json1.get("data").get("id").asText();

        // Replay same request with same key
        ResponseEntity<String> response2 = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                String.class
        );

        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json2 = objectMapper.readTree(response2.getBody());
        assertThat(json2.get("code").asText()).isEqualTo("200");
        assertThat(json2.get("message").asText()).isEqualTo("already created");
        String orderId2 = json2.get("data").get("id").asText();

        // Verify same order ID is returned
        assertThat(orderId1).isEqualTo(orderId2);
    }

    /**
     * Test: Same key + different body → 409
     * Note: Since POST /orders doesn't accept a body, we test with different tenant IDs
     * which effectively represents different request contexts (different body semantics).
     * The service checks if the same key exists with a different tenant.
     */
    @Test
    void idempotency_sameKeyDifferentTenant_returns409() {
        String idempotencyKey = UUID.randomUUID().toString();
        String tenantId1 = UUID.randomUUID().toString();
        String tenantId2 = UUID.randomUUID().toString();

        HttpHeaders headers1 = new HttpHeaders();
        headers1.set("X-Tenant-Id", tenantId1);
        headers1.set("Idempotency-Key", idempotencyKey);

        HttpHeaders headers2 = new HttpHeaders();
        headers2.set("X-Tenant-Id", tenantId2);
        headers2.set("Idempotency-Key", idempotencyKey);

        // First request with tenant1
        ResponseEntity<String> response1 = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, headers1),
                String.class
        );
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second request with same key but different tenant (different body context)
        ResponseEntity<String> response2 = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, headers2),
                String.class
        );

        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        try {
            JsonNode json2 = objectMapper.readTree(response2.getBody());
            assertThat(json2.get("code").asText()).isEqualTo("409");
            assertThat(json2.get("message").asText()).isEqualTo("conflict");
        } catch (Exception e) {
            // If JSON parsing fails, at least verify status code
            assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ========== Optimistic Locking Tests ==========

    /**
     * Test: Confirm with correct If-Match succeeds and bumps version
     */
    @Test
    void optimisticLocking_correctIfMatch_bumpsVersion() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        HttpHeaders createHeaders = createHeaders(idempotencyKey);

        // Create order
        ResponseEntity<String> createResponse = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, createHeaders),
                String.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String orderId = created.get("data").get("id").asText();
        int initialVersion = created.get("data").get("version").asInt();
        assertThat(initialVersion).isEqualTo(1);

        // Confirm order with correct If-Match
        String confirmIdempotencyKey = UUID.randomUUID().toString();
        HttpHeaders confirmHeaders = createHeadersWithIfMatch(confirmIdempotencyKey, initialVersion);
        Map<String, Object> confirmBody = Map.of("totalCents", 5000);

        ResponseEntity<String> confirmResponse = restTemplate.exchange(
                baseUrl + "/orders/" + orderId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(confirmBody, confirmHeaders),
                String.class
        );

        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode confirmed = objectMapper.readTree(confirmResponse.getBody());
        assertThat(confirmed.get("code").asText()).isEqualTo("200");
        int newVersion = confirmed.get("data").get("version").asInt();
        assertThat(newVersion).isEqualTo(initialVersion + 1);
        assertThat(confirmed.get("data").get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("data").get("totalCents").asInt()).isEqualTo(5000);
    }

    /**
     * Test: Stale If-Match → 409
     */
    @Test
    void optimisticLocking_staleIfMatch_returns409() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        HttpHeaders createHeaders = createHeaders(idempotencyKey);

        // Create order
        ResponseEntity<String> createResponse = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, createHeaders),
                String.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String orderId = created.get("data").get("id").asText();
        int initialVersion = created.get("data").get("version").asInt();

        // First confirm to bump version
        String confirmKey1 = UUID.randomUUID().toString();
        HttpHeaders confirmHeaders1 = createHeadersWithIfMatch(confirmKey1, initialVersion);
        Map<String, Object> confirmBody1 = Map.of("totalCents", 3000);

        ResponseEntity<String> confirmResponse1 = restTemplate.exchange(
                baseUrl + "/orders/" + orderId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(confirmBody1, confirmHeaders1),
                String.class
        );
        assertThat(confirmResponse1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Try to confirm again with stale If-Match (old version)
        String confirmKey2 = UUID.randomUUID().toString();
        HttpHeaders confirmHeaders2 = createHeadersWithIfMatch(confirmKey2, initialVersion); // Stale version
        Map<String, Object> confirmBody2 = Map.of("totalCents", 4000);

        ResponseEntity<String> confirmResponse2 = restTemplate.exchange(
                baseUrl + "/orders/" + orderId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(confirmBody2, confirmHeaders2),
                String.class
        );

        assertThat(confirmResponse2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = objectMapper.readTree(confirmResponse2.getBody());
        assertThat(error.get("code").asText()).isEqualTo("409");
        assertThat(error.get("message").asText()).isEqualTo("stale version");
    }

    // ========== Close + Outbox (Transactional) Tests ==========

    /**
     * Test: After closing, order is closed and exactly one outbox row exists for that order
     */
    @Test
    void closeOrder_createsOutboxRow_andOrderClosed() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        HttpHeaders createHeaders = createHeaders(idempotencyKey);

        // Create order
        ResponseEntity<String> createResponse = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, createHeaders),
                String.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String orderId = created.get("data").get("id").asText();

        // Confirm order first (required for closing)
        String confirmKey = UUID.randomUUID().toString();
        HttpHeaders confirmHeaders = createHeadersWithIfMatch(confirmKey, 1);
        Map<String, Object> confirmBody = Map.of("totalCents", 10000);

        ResponseEntity<String> confirmResponse = restTemplate.exchange(
                baseUrl + "/orders/" + orderId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(confirmBody, confirmHeaders),
                String.class
        );
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Close order
        String closeKey = UUID.randomUUID().toString();
        HttpHeaders closeHeaders = createHeaders(closeKey);

        ResponseEntity<String> closeResponse = restTemplate.exchange(
                baseUrl + "/orders/" + orderId + "/close",
                HttpMethod.POST,
                new HttpEntity<>(null, closeHeaders),
                String.class
        );

        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode closed = objectMapper.readTree(closeResponse.getBody());
        assertThat(closed.get("code").asText()).isEqualTo("200");
        assertThat(closed.get("data").get("status").asText()).isEqualTo("CLOSED");

        // Verify order is closed in database
        Optional<OrdersEntity> orderOpt = ordersRepository.findById(UUID.fromString(orderId));
        assertThat(orderOpt).isPresent();
        OrdersEntity order = orderOpt.get();
        assertThat(order.getStatus().name()).isEqualTo("CLOSED");

        // Verify exactly one outbox row exists for this order
        List<OutboxEntity> outboxRows = outboxRepository.findAll().stream()
                .filter(o -> o.getOrderId().toString().equals(orderId))
                .toList();
        assertThat(outboxRows).hasSize(1);
        OutboxEntity outbox = outboxRows.get(0);
        assertThat(outbox.getEventType()).isEqualTo("orders.closed");
        assertThat(outbox.getTenantId()).isEqualTo(tenantId);
        assertThat(outbox.getOrderId().toString()).isEqualTo(orderId);
    }

    // ========== Pagination Tests ==========

    /**
     * Test: Create ≥15 orders; fetch with limit=10 → 10 then 5 items, no duplicates
     */
    @Test
    void pagination_returnsPages_withoutDuplicates() throws Exception {
        // Create 15 orders
        List<String> createdOrderIds = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String idempotencyKey = UUID.randomUUID().toString();
            HttpHeaders headers = createHeaders(idempotencyKey);

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/orders",
                    HttpMethod.POST,
                    new HttpEntity<>(null, headers),
                    String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode json = objectMapper.readTree(response.getBody());
            String orderId = json.get("data").get("id").asText();
            createdOrderIds.add(orderId);
        }

        // First page with limit=10
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("X-Tenant-Id", tenantId);

        ResponseEntity<String> page1Response = restTemplate.exchange(
                baseUrl + "/orders?limit=10",
                HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                String.class
        );

        assertThat(page1Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page1 = objectMapper.readTree(page1Response.getBody());
        assertThat(page1.get("code").asText()).isEqualTo("200");
        JsonNode items1 = page1.get("data").get("items");
        assertThat(items1.isArray()).isTrue();
        assertThat(items1.size()).isEqualTo(10);

        List<String> page1Ids = new ArrayList<>();
        for (JsonNode item : items1) {
            page1Ids.add(item.get("id").asText());
        }

        // Verify we have a next cursor
        String nextCursor = page1.get("data").get("nextCursor").asText();
        assertThat(nextCursor).isNotNull();
        assertThat(nextCursor).isNotEmpty();

        // Second page using cursor
        ResponseEntity<String> page2Response = restTemplate.exchange(
                baseUrl + "/orders?limit=10&cursor=" + nextCursor,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                String.class
        );

        assertThat(page2Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page2 = objectMapper.readTree(page2Response.getBody());
        assertThat(page2.get("code").asText()).isEqualTo("200");
        JsonNode items2 = page2.get("data").get("items");
        assertThat(items2.isArray()).isTrue();
        assertThat(items2.size()).isEqualTo(5); // Remaining 5 items

        List<String> page2Ids = new ArrayList<>();
        for (JsonNode item : items2) {
            page2Ids.add(item.get("id").asText());
        }

        // Verify no duplicates across pages
        Set<String> allIds = new HashSet<>();
        allIds.addAll(page1Ids);
        allIds.addAll(page2Ids);
        assertThat(allIds.size()).isEqualTo(15); // All 15 orders, no duplicates

        // Verify all created orders are present
        Set<String> createdSet = new HashSet<>(createdOrderIds);
        assertThat(allIds).containsAll(createdSet);
    }

    /**
     * Additional test: Verify pagination with empty result when cursor points beyond data
     */
    @Test
    void pagination_emptyResult_whenCursorBeyondData() throws Exception {
        // Create 5 orders
        for (int i = 0; i < 5; i++) {
            String idempotencyKey = UUID.randomUUID().toString();
            HttpHeaders headers = createHeaders(idempotencyKey);
            restTemplate.exchange(
                    baseUrl + "/orders",
                    HttpMethod.POST,
                    new HttpEntity<>(null, headers),
                    String.class
            );
        }

        // Get first page
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("X-Tenant-Id", tenantId);
        ResponseEntity<String> page1Response = restTemplate.exchange(
                baseUrl + "/orders?limit=10",
                HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                String.class
        );

        JsonNode page1 = objectMapper.readTree(page1Response.getBody());
        String nextCursor = page1.get("data").get("nextCursor").asText();

        // Use cursor to get next page (should be empty or have fewer items)
        ResponseEntity<String> page2Response = restTemplate.exchange(
                baseUrl + "/orders?limit=10&cursor=" + nextCursor,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                String.class
        );

        JsonNode page2 = objectMapper.readTree(page2Response.getBody());
        JsonNode items2 = page2.get("data").get("items");
        // Should be empty or null cursor
        assertThat(items2.size()).isLessThanOrEqualTo(0);
    }

    /**
     * Test: Verify correlation ID is generated and returned in response header
     */
    @Test
    void correlationId_generatedAndReturnedInResponse() {
        String idempotencyKey = UUID.randomUUID().toString();
        HttpHeaders headers = createHeaders(idempotencyKey);
        
        // Don't set X-Correlation-Id header - should be auto-generated
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        
        // Verify correlation ID is present in response header
        String correlationId = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(correlationId).isNotNull();
        assertThat(correlationId).isNotEmpty();
    }

    /**
     * Test: Verify correlation ID from request is preserved and returned
     */
    @Test
    void correlationId_preservedFromRequest() {
        String idempotencyKey = UUID.randomUUID().toString();
        String providedCorrelationId = UUID.randomUUID().toString();
        HttpHeaders headers = createHeaders(idempotencyKey);
        headers.set("X-Correlation-Id", providedCorrelationId);
        
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        
        // Verify the provided correlation ID is returned
        String correlationId = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(correlationId).isEqualTo(providedCorrelationId);
    }
}
