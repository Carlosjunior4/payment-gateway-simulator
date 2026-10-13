package com.portfolio.paymentgateway.domain.model;

import java.util.Currency;
import java.util.Objects;

public record Money(long amountMinor, Currency currency) {

    public Money {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        Objects.requireNonNull(currency, "Currency is required");
    }

    public static Money ofMinorUnits(long amountMinor, String currencyCode) {
        Objects.requireNonNull(currencyCode, "Currency code is required");
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }
}
