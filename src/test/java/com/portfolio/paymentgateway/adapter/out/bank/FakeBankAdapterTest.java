package com.portfolio.paymentgateway.adapter.out.bank;

import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;
import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;
import com.portfolio.paymentgateway.application.service.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeBankAdapterTest {

    private static final UUID PAYMENT_ID = UUID.fromString("d88502c7-908e-4a2c-ae5c-91d74285d7c0");
    private static final UUID MERCHANT_ID = UUID.fromString("06472856-9c9a-43c9-8b01-1bc745efbce2");

    @Test
    void returnsStableAuthorizationEventForRetries() {
        FakeBankAdapter bank = new FakeBankAdapter(
                new CircuitBreaker(3, Duration.ofSeconds(10), Clock.systemUTC()),
                false
        );
        CreatePaymentCommand command = new CreatePaymentCommand(1299, Currency.getInstance("BRL"));

        BankWebhookEvent first = bank.charge(PAYMENT_ID, MERCHANT_ID, command);
        BankWebhookEvent retry = bank.charge(PAYMENT_ID, MERCHANT_ID, command);

        assertEquals("evt-" + PAYMENT_ID, first.eventId());
        assertEquals(first.eventId(), retry.eventId());
        assertEquals(BankWebhookEvent.Outcome.AUTHORIZED, first.outcome());
        assertEquals("bank-" + PAYMENT_ID, first.bankReference());
    }

    @Test
    void deterministicallyDeclinesAmountsAboveSimulatorLimit() {
        FakeBankAdapter bank = new FakeBankAdapter(
                new CircuitBreaker(3, Duration.ofSeconds(10), Clock.systemUTC()),
                false
        );

        BankWebhookEvent event = bank.charge(
                PAYMENT_ID,
                MERCHANT_ID,
                new CreatePaymentCommand(1_000_000, Currency.getInstance("BRL"))
        );

        assertEquals(BankWebhookEvent.Outcome.DECLINED, event.outcome());
        assertEquals("simulated_limit_exceeded", event.failureCode());
        assertNull(event.bankReference());
    }

    @Test
    void appliesCircuitBreakerToSimulatedBankOutages() {
        FakeBankAdapter bank = new FakeBankAdapter(
                new CircuitBreaker(1, Duration.ofSeconds(10), Clock.systemUTC()),
                true
        );
        CreatePaymentCommand command = new CreatePaymentCommand(1299, Currency.getInstance("BRL"));

        assertThrows(FakeBankAdapter.BankSimulatorUnavailableException.class,
                () -> bank.charge(PAYMENT_ID, MERCHANT_ID, command));
        assertThrows(CircuitBreaker.CircuitOpenException.class,
                () -> bank.charge(PAYMENT_ID, MERCHANT_ID, command));
    }
}
