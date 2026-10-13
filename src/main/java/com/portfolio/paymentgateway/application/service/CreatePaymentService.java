package com.portfolio.paymentgateway.application.service;

import com.portfolio.paymentgateway.application.port.in.CreatePaymentUseCase;
import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;
import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import com.portfolio.paymentgateway.application.port.out.IdempotentPaymentStore;
import com.portfolio.paymentgateway.domain.model.Money;
import com.portfolio.paymentgateway.domain.model.Payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

public class CreatePaymentService implements CreatePaymentUseCase {

    private final IdempotentPaymentStore paymentStore;
    private final Clock clock;

    public CreatePaymentService(IdempotentPaymentStore paymentStore) {
        this(paymentStore, Clock.systemUTC());
    }

    CreatePaymentService(IdempotentPaymentStore paymentStore, Clock clock) {
        this.paymentStore = paymentStore;
        this.clock = clock;
    }

    @Override
    public PaymentResponse create(UUID merchantId, String idempotencyKey, CreatePaymentCommand command) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant ID is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency key must contain 1 to 255 characters");
        }
        if (command == null || command.currency() == null) {
            throw new IllegalArgumentException("Payment amount and currency are required");
        }

        Money amount = new Money(command.amountMinor(), command.currency());
        String requestHash = hashRequest(amount);
        var now = clock.instant();
        Payment payment = Payment.pending(UUID.randomUUID(), merchantId, amount, now);
        PaymentResponse response = new PaymentResponse(
                payment.id(),
                merchantId,
                amount.amountMinor(),
                amount.currency().getCurrencyCode(),
                payment.status(),
                payment.createdAt()
        );

        return paymentStore.createOrReplay(merchantId, idempotencyKey, requestHash, payment, response);
    }

    private String hashRequest(Money amount) {
        String canonicalRequest = amount.amountMinor() + "\n" + amount.currency().getCurrencyCode();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
