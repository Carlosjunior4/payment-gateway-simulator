package com.portfolio.paymentgateway.application.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency key was already used with a different request");
    }
}
