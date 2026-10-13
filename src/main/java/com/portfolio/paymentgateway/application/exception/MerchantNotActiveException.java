package com.portfolio.paymentgateway.application.exception;

import java.util.UUID;

public class MerchantNotActiveException extends RuntimeException {

    public MerchantNotActiveException(UUID merchantId) {
        super("Merchant is not active: " + merchantId);
    }
}
