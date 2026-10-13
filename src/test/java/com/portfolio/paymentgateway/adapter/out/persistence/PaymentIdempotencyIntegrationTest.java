package com.portfolio.paymentgateway.adapter.out.persistence;

import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;
import com.portfolio.paymentgateway.application.port.out.AsyncPaymentProcessingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "payment-processing.poll-interval=1h",
        "webhook-processing.poll-interval=1h",
        "payment-rate-limit.max-requests=2"
})
@Testcontainers(disabledWithoutDocker = true)
class PaymentIdempotencyIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AsyncPaymentProcessingStore processingStore;

    @LocalServerPort
    private int port;

    private UUID merchantId;

    @BeforeEach
    void resetDatabaseAndCreateMerchant() {
        jdbcTemplate.update("DELETE FROM webhook_inbox");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM payments");
        jdbcTemplate.update("DELETE FROM merchants");
        merchantId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO merchants (id, name, status)
                        VALUES (?, 'Integration Test Merchant', 'ACTIVE')
                        """,
                merchantId
        );
    }

    @Test
    void persistsPaymentAndReplaysOriginalResponseForSameRequest() {
        ResponseEntity<PaymentResponse> first = post(merchantId, "integration-key", 1250);
        ResponseEntity<PaymentResponse> replay = post(merchantId, "integration-key", 1250);

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, replay.getStatusCode());
        assertEquals(body(first).id(), body(replay).id());
        assertEquals(1, count("payments"));
        assertEquals(1, count("idempotency_keys"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void rejectsDifferentRequestForAnExistingKeyWithoutCreatingAnotherPayment() {
        ResponseEntity<PaymentResponse> original = post(merchantId, "conflict-key", 1250);
        ResponseEntity<String> conflict = postRaw(merchantId, "conflict-key", 1251);

        assertEquals(HttpStatus.ACCEPTED, original.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(Objects.requireNonNull(conflict.getBody()).contains("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, count("payments"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void scopesIdempotencyKeysToMerchant() {
        UUID secondMerchant = UUID.randomUUID();
        insertMerchant(secondMerchant);

        ResponseEntity<PaymentResponse> first = post(merchantId, "scoped-key", 1250);
        ResponseEntity<PaymentResponse> second = post(secondMerchant, "scoped-key", 1250);

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode());
        assertNotEquals(body(first).id(), body(second).id());
        assertEquals(2, count("payments"));
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOnePayment() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<PaymentResponse>> request = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent request start timed out");
                }
                return post(merchantId, "concurrent-key", 1250);
            };
            Future<ResponseEntity<PaymentResponse>> firstFuture = executor.submit(request);
            Future<ResponseEntity<PaymentResponse>> secondFuture = executor.submit(request);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            ResponseEntity<PaymentResponse> first = firstFuture.get();
            ResponseEntity<PaymentResponse> second = secondFuture.get();

            assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
            assertEquals(HttpStatus.ACCEPTED, second.getStatusCode());
            assertEquals(body(first).id(), body(second).id());
            assertEquals(1, count("payments"));
            assertEquals(1, count("outbox_events"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesMerchantRateLimitUsingRedis() {
        ResponseEntity<PaymentResponse> first = post(merchantId, "limit-1", 1250);
        ResponseEntity<PaymentResponse> second = post(merchantId, "limit-2", 1250);
        ResponseEntity<String> rejected = postRaw(merchantId, "limit-3", 1250);

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
        assertTrue(rejected.getHeaders().containsKey("Retry-After"));
        assertEquals(2, count("payments"));
    }

    @Test
    void limitsAreIsolatedPerMerchant() {
        UUID secondMerchant = UUID.randomUUID();
        insertMerchant(secondMerchant);

        assertEquals(HttpStatus.ACCEPTED, post(merchantId, "first-merchant-1", 1250).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, post(merchantId, "first-merchant-2", 1250).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, post(secondMerchant, "second-merchant-1", 1250).getStatusCode());
    }

    @Test
    void suspendedMerchantCannotCreatePayments() {
        UUID suspendedMerchant = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO merchants (id, name, status) VALUES (?, 'Suspended Merchant', 'SUSPENDED')
                        """,
                suspendedMerchant
        );

        ResponseEntity<String> response = postRaw(suspendedMerchant, "suspended-key", 1250);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(Objects.requireNonNull(response.getBody()).contains("MERCHANT_NOT_ACTIVE"));
        assertEquals(0, count("payments"));
        assertEquals(0, count("idempotency_keys"));
    }

    @Test
    void processesBankResultAsynchronouslyAndKeepsTerminalStateMonotonic() {
        ResponseEntity<PaymentResponse> created = post(merchantId, "async-flow", 1250);
        UUID paymentId = body(created).id();
        AsyncPaymentProcessingStore.PendingBankCharge charge =
                processingStore.claimPendingBankCharges(1).get(0);
        processingStore.recordBankChargeAttempt(charge);

        BankWebhookEvent authorized = new BankWebhookEvent(
                "bank-event-1",
                paymentId,
                1250,
                "BRL",
                BankWebhookEvent.Outcome.AUTHORIZED,
                "bank-ref-1",
                null
        );
        String authorizedPayload = """
                {"eventId":"bank-event-1","paymentId":"%s","amountMinor":1250,
                 "currency":"BRL","outcome":"AUTHORIZED","bankReference":"bank-ref-1","failureCode":null}
                """.formatted(paymentId);
        assertTrue(processingStore.receiveWebhook("fake-bank", authorized, authorizedPayload));
        assertFalse(processingStore.receiveWebhook("fake-bank", authorized, authorizedPayload));
        var pendingWebhooks = processingStore.claimPendingWebhooks(1);
        assertEquals(1, pendingWebhooks.size());
        processingStore.processWebhook(pendingWebhooks.get(0));

        processingStore.completeBankCharge(charge);
        BankWebhookEvent lateDecline = new BankWebhookEvent(
                "bank-event-2",
                paymentId,
                1250,
                "BRL",
                BankWebhookEvent.Outcome.DECLINED,
                null,
                "late_decline"
        );
        assertTrue(processingStore.receiveWebhook("fake-bank", lateDecline, """
                {"eventId":"bank-event-2","paymentId":"%s","amountMinor":1250,
                 "currency":"BRL","outcome":"DECLINED","bankReference":null,"failureCode":"late_decline"}
                """.formatted(paymentId)));
        processingStore.processWebhook(processingStore.claimPendingWebhooks(1).get(0));

        ResponseEntity<PaymentResponse> payment = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/payments/" + paymentId,
                HttpMethod.GET,
                new HttpEntity<>(headers(merchantId, "read-status")),
                PaymentResponse.class
        );
        assertEquals(HttpStatus.OK, payment.getStatusCode());
        assertEquals("AUTHORIZED", body(payment).status().name());
        assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                "SELECT status FROM payment_attempts WHERE payment_id = ?", String.class, paymentId));
        assertEquals(2, count("webhook_inbox"));
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT count(*) FROM webhook_inbox WHERE status = 'PROCESSED'", Integer.class));
    }

    private void insertMerchant(UUID id) {
        jdbcTemplate.update("""
                        INSERT INTO merchants (id, name, status) VALUES (?, 'Second Merchant', 'ACTIVE')
                        """,
                id
        );
    }

    private ResponseEntity<PaymentResponse> post(UUID merchant, String key, long amountMinor) {
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/payments",
                new HttpEntity<>(requestBody(amountMinor), headers(merchant, key)),
                PaymentResponse.class
        );
    }

    private ResponseEntity<String> postRaw(UUID merchant, String key, long amountMinor) {
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/payments",
                new HttpEntity<>(requestBody(amountMinor), headers(merchant, key)),
                String.class
        );
    }

    private String requestBody(long amountMinor) {
        return "{\"amountMinor\":" + amountMinor + ",\"currency\":\"BRL\"}";
    }

    private HttpHeaders headers(UUID merchant, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchant.toString());
        headers.set("Idempotency-Key", key);
        return headers;
    }

    private int count(String table) {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class)
        );
    }

    private PaymentResponse body(ResponseEntity<PaymentResponse> response) {
        return Objects.requireNonNull(response.getBody(), "Expected a payment response body");
    }
}
