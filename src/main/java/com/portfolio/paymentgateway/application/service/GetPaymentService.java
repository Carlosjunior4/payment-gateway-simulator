package com.portfolio.paymentgateway.application.service;

import com.portfolio.paymentgateway.application.port.in.GetPaymentUseCase;
import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import com.portfolio.paymentgateway.application.port.out.IdempotentPaymentStore;

import java.util.Optional;
import java.util.UUID;

public class GetPaymentService implements GetPaymentUseCase {

    private final IdempotentPaymentStore paymentStore;

    public GetPaymentService(IdempotentPaymentStore paymentStore) {
        this.paymentStore = paymentStore;
    }

    @Override
    public Optional<PaymentResponse> get(UUID merchantId, UUID paymentId) {
        return paymentStore.findByMerchantAndPaymentId(merchantId, paymentId);
    }
}
