package com.portfolio.paymentgateway.adapter.in.web;

public class InvalidBankSignatureException extends RuntimeException {

    public InvalidBankSignatureException() {
        super("Bank webhook signature is invalid");
    }
}
