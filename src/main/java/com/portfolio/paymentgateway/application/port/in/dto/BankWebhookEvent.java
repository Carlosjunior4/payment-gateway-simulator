package com.portfolio.paymentgateway.application.port.in.dto;

import java.util.UUID;

public record BankWebhookEvent(
        String eventId,
        UUID paymentId,
        long amountMinor,
        String currency,
        Outcome outcome,
        String bankReference,
        String failureCode
) {
    public enum Outcome {
        AUTHORIZED,
        DECLINED
    }
}
