package com.portfolio.paymentgateway.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentTest {

    private static final UUID PAYMENT_ID = UUID.fromString("d88502c7-908e-4a2c-ae5c-91d74285d7c0");
    private static final UUID MERCHANT_ID = UUID.fromString("06472856-9c9a-43c9-8b01-1bc745efbce2");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-01-01T10:00:05Z");

    @Test
    void startsPendingAndCanBeAuthorizedOnce() {
        Payment payment = newPayment();

        payment.authorize("bank-ref-123", UPDATED_AT);

        assertEquals(PaymentStatus.AUTHORIZED, payment.status());
        assertEquals("bank-ref-123", payment.bankReference());
        assertEquals(CREATED_AT, payment.createdAt());
        assertEquals(UPDATED_AT, payment.updatedAt());
    }

    @Test
    void canBeDeclinedWithAReason() {
        Payment payment = newPayment();

        payment.decline("insufficient_funds", UPDATED_AT);

        assertEquals(PaymentStatus.DECLINED, payment.status());
        assertEquals("insufficient_funds", payment.failureCode());
    }

    @Test
    void cannotTransitionAfterReachingATerminalState() {
        Payment payment = newPayment();
        payment.authorize("bank-ref-123", UPDATED_AT);

        assertThrows(IllegalStateException.class, () -> payment.fail("timeout", UPDATED_AT));
    }

    @Test
    void rejectsBlankBankReference() {
        Payment payment = newPayment();

        assertThrows(IllegalArgumentException.class, () -> payment.authorize(" ", UPDATED_AT));
        assertEquals(PaymentStatus.PENDING, payment.status());
    }

    @Test
    void rejectsInvalidTransitionTimeWithoutChangingPayment() {
        Payment payment = newPayment();

        assertThrows(NullPointerException.class, () -> payment.authorize("bank-ref-123", null));
        assertEquals(PaymentStatus.PENDING, payment.status());
        assertEquals(null, payment.bankReference());
        assertEquals(CREATED_AT, payment.updatedAt());
    }

    private Payment newPayment() {
        return Payment.pending(PAYMENT_ID, MERCHANT_ID, Money.ofMinorUnits(1299, "BRL"), CREATED_AT);
    }
}
