package com.portfolio.paymentgateway.adapter.out.bank;

import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;
import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;
import com.portfolio.paymentgateway.application.port.out.BankChargePort;
import com.portfolio.paymentgateway.application.service.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FakeBankAdapter implements BankChargePort {

    private static final long DECLINE_THRESHOLD_MINOR = 1_000_000;

    private final CircuitBreaker circuitBreaker;
    private final boolean simulateFailure;

    public FakeBankAdapter(
            CircuitBreaker circuitBreaker,
            @Value("${bank.simulator.fail-requests:false}") boolean simulateFailure
    ) {
        this.circuitBreaker = circuitBreaker;
        this.simulateFailure = simulateFailure;
    }

    @Override
    public BankWebhookEvent charge(UUID paymentId, UUID merchantId, CreatePaymentCommand command) {
        String eventId = "evt-" + paymentId;
        return circuitBreaker.execute(() -> {
            if (simulateFailure) {
                throw new BankSimulatorUnavailableException();
            }
            if (command.amountMinor() >= DECLINE_THRESHOLD_MINOR) {
                return new BankWebhookEvent(
                        eventId,
                        paymentId,
                        command.amountMinor(),
                        command.currency().getCurrencyCode(),
                        BankWebhookEvent.Outcome.DECLINED,
                        null,
                        "simulated_limit_exceeded"
                );
            }
            String reference = "bank-" + paymentId;
            return new BankWebhookEvent(
                    eventId,
                    paymentId,
                    command.amountMinor(),
                    command.currency().getCurrencyCode(),
                    BankWebhookEvent.Outcome.AUTHORIZED,
                    reference,
                    null
            );
        });
    }

    public static class BankSimulatorUnavailableException extends RuntimeException {

        public BankSimulatorUnavailableException() {
            super("Fake bank simulator is configured as unavailable");
        }
    }
}
