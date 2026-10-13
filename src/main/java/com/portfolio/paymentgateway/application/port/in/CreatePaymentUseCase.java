package com.portfolio.paymentgateway.application.port.in;

import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;
import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;

import java.util.UUID;

public interface CreatePaymentUseCase {

    PaymentResponse create(UUID merchantId, String idempotencyKey, CreatePaymentCommand command);
}
