package com.portfolio.paymentgateway.application.port.out;

import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import com.portfolio.paymentgateway.domain.model.Payment;

import java.util.Optional;
import java.util.UUID;

public interface IdempotentPaymentStore {

    PaymentResponse createOrReplay(
            UUID merchantId,
            String idempotencyKey,
            String requestHash,
            Payment payment,
            PaymentResponse initialResponse
    );

    Optional<PaymentResponse> findByMerchantAndPaymentId(UUID merchantId, UUID paymentId);
}
