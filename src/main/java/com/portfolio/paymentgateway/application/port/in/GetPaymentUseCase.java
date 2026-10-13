package com.portfolio.paymentgateway.application.port.in;

import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;

import java.util.Optional;
import java.util.UUID;

public interface GetPaymentUseCase {

    Optional<PaymentResponse> get(UUID merchantId, UUID paymentId);
}
