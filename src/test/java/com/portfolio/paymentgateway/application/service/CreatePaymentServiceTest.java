package com.portfolio.paymentgateway.application.service;

import com.portfolio.paymentgateway.application.exception.IdempotencyConflictException;
import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;
import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import com.portfolio.paymentgateway.application.port.out.IdempotentPaymentStore;
import com.portfolio.paymentgateway.domain.model.Payment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreatePaymentServiceTest {

    private static final UUID MERCHANT_ID = UUID.fromString("06472856-9c9a-43c9-8b01-1bc745efbce2");
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void canonicalizesCurrencyAndCreatesPendingPayment() {
        CapturingPaymentStore store = new CapturingPaymentStore();
        CreatePaymentService service = newService(store);

        PaymentResponse response = service.create(
                MERCHANT_ID,
                "request-1",
                new CreatePaymentCommand(1299, Currency.getInstance("BRL"))
        );

        assertEquals(store.payment.get().id(), response.id());
        assertEquals(MERCHANT_ID, store.payment.get().merchantId());
        assertEquals(1299, store.payment.get().amount().amountMinor());
        assertEquals("BRL", response.currency());
        assertEquals(NOW, response.createdAt());
        assertEquals("PENDING", response.status().name());
    }

    @Test
    void sameRequestProducesSameHashForIdempotentReplay() {
        CapturingPaymentStore store = new CapturingPaymentStore();
        CreatePaymentService service = newService(store);

        service.create(MERCHANT_ID, "request-1",
                new CreatePaymentCommand(1299, Currency.getInstance("BRL")));
        String firstHash = store.requestHash.get();

        service.create(MERCHANT_ID, "request-1",
                new CreatePaymentCommand(1299, Currency.getInstance("BRL")));

        assertEquals(firstHash, store.requestHash.get());
        assertEquals(2, store.calls);
    }

    @Test
    void differentRequestIsPresentedToStoreWithDifferentHash() {
        CapturingPaymentStore store = new CapturingPaymentStore();
        CreatePaymentService service = newService(store);

        service.create(MERCHANT_ID, "request-1",
                new CreatePaymentCommand(1299, Currency.getInstance("BRL")));
        String firstHash = store.requestHash.get();

        service.create(MERCHANT_ID, "request-1",
                new CreatePaymentCommand(1300, Currency.getInstance("BRL")));

        assertNotEquals(firstHash, store.requestHash.get());
    }

    @Test
    void rejectsInvalidIdempotencyKeysBeforePersistence() {
        CapturingPaymentStore store = new CapturingPaymentStore();
        CreatePaymentService service = newService(store);

        assertThrows(IllegalArgumentException.class, () -> service.create(
                MERCHANT_ID, " ", new CreatePaymentCommand(100, Currency.getInstance("BRL"))));
        assertEquals(0, store.calls);
    }

    private CreatePaymentService newService(CapturingPaymentStore store) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new CreatePaymentService(store, clock);
    }

    private static final class CapturingPaymentStore implements IdempotentPaymentStore {
        private final AtomicReference<Payment> payment = new AtomicReference<>();
        private final AtomicReference<String> requestHash = new AtomicReference<>();
        private int calls;

        @Override
        public PaymentResponse createOrReplay(
                UUID merchantId,
                String idempotencyKey,
                String hash,
                Payment newPayment,
                PaymentResponse initialResponse
        ) {
            calls++;
            payment.set(newPayment);
            requestHash.set(hash);
            return initialResponse;
        }

        @Override
        public java.util.Optional<PaymentResponse> findByMerchantAndPaymentId(UUID merchantId, UUID paymentId) {
            return java.util.Optional.empty();
        }
    }
}
