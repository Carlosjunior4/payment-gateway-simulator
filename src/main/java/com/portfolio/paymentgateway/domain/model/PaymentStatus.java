package com.portfolio.paymentgateway.domain.model;

public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    DECLINED,
    FAILED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
