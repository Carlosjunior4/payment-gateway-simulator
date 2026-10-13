package com.portfolio.paymentgateway.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Payment {

    private final UUID id;
    private final UUID merchantId;
    private final Money amount;
    private final Instant createdAt;
    private PaymentStatus status;
    private String bankReference;
    private String failureCode;
    private Instant updatedAt;

    private Payment(UUID id, UUID merchantId, Money amount, Instant now) {
        this.id = Objects.requireNonNull(id, "Payment ID is required");
        this.merchantId = Objects.requireNonNull(merchantId, "Merchant ID is required");
        this.amount = Objects.requireNonNull(amount, "Payment amount is required");
        this.createdAt = Objects.requireNonNull(now, "Creation time is required");
        this.updatedAt = now;
        this.status = PaymentStatus.PENDING;
    }

    public static Payment pending(UUID id, UUID merchantId, Money amount, Instant now) {
        return new Payment(id, merchantId, amount, now);
    }

    public void authorize(String bankReference, Instant now) {
        requirePending();
        String validatedBankReference = requireText(bankReference, "Bank reference is required");
        Instant transitionTime = requireTime(now);
        this.bankReference = validatedBankReference;
        this.status = PaymentStatus.AUTHORIZED;
        this.updatedAt = transitionTime;
    }

    public void decline(String failureCode, Instant now) {
        markFailed(PaymentStatus.DECLINED, failureCode, now);
    }

    public void fail(String failureCode, Instant now) {
        markFailed(PaymentStatus.FAILED, failureCode, now);
    }

    private void markFailed(PaymentStatus terminalStatus, String code, Instant now) {
        requirePending();
        String validatedFailureCode = requireText(code, "Failure code is required");
        Instant transitionTime = requireTime(now);
        this.failureCode = validatedFailureCode;
        this.status = terminalStatus;
        this.updatedAt = transitionTime;
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Only pending payments can transition");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static Instant requireTime(Instant value) {
        return Objects.requireNonNull(value, "Transition time is required");
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public String bankReference() {
        return bankReference;
    }

    public String failureCode() {
        return failureCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
