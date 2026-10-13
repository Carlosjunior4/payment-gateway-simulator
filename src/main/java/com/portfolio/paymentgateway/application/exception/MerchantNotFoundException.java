package com.portfolio.paymentgateway.application.exception;

import java.util.UUID;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(UUID merchantId) {
        super("Merchant not found: " + merchantId);
    }
}
