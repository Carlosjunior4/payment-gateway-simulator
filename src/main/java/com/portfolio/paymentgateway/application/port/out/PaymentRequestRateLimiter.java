package com.portfolio.paymentgateway.application.port.out;

import java.util.UUID;

public interface PaymentRequestRateLimiter {

    Decision consume(UUID merchantId);

    record Decision(boolean allowed, int retryAfterSeconds) {
    }
}
