package com.portfolio.paymentgateway.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void createsMoneyFromMinorUnitsAndIsoCurrency() {
        Money money = Money.ofMinorUnits(2500, "BRL");

        assertEquals(2500, money.amountMinor());
        assertEquals("BRL", money.currency().getCurrencyCode());
    }

    @Test
    void rejectsZeroOrNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinorUnits(0, "BRL"));
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinorUnits(-1, "BRL"));
    }

    @Test
    void rejectsUnknownCurrencyCodes() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinorUnits(100, "ZZZ"));
    }
}
