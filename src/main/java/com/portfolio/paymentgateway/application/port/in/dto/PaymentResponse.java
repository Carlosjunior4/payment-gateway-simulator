package com.portfolio.paymentgateway.application.port.in.dto;

import com.portfolio.paymentgateway.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID merchantId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        Instant createdAt
) {
}
