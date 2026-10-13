package com.portfolio.paymentgateway.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreatePaymentRequest(
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency
) {
}
