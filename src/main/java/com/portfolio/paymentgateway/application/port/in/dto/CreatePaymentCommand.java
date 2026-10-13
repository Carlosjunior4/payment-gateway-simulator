package com.portfolio.paymentgateway.application.port.in.dto;

import java.util.Currency;

public record CreatePaymentCommand(long amountMinor, Currency currency) {
}
