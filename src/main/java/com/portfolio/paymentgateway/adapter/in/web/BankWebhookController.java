package com.portfolio.paymentgateway.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;
import com.portfolio.paymentgateway.application.port.out.AsyncPaymentProcessingStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Currency;

@RestController
@RequestMapping("/api/v1/webhooks/fake-bank")
public class BankWebhookController {

    private static final String PROVIDER = "fake-bank";

    private final WebhookSignatureVerifier signatureVerifier;
    private final AsyncPaymentProcessingStore processingStore;
    private final ObjectMapper objectMapper;

    public BankWebhookController(
            WebhookSignatureVerifier signatureVerifier,
            AsyncPaymentProcessingStore processingStore,
            ObjectMapper objectMapper
    ) {
        this.signatureVerifier = signatureVerifier;
        this.processingStore = processingStore;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<WebhookReceipt> receive(
            @RequestHeader(value = "X-Bank-Signature", required = false) String signature,
            @RequestBody byte[] rawPayload
    ) {
        if (!signatureVerifier.isValid(signature, rawPayload)) {
            throw new InvalidBankSignatureException();
        }
        BankWebhookEvent event = parseAndValidate(rawPayload);
        boolean inserted = processingStore.receiveWebhook(
                PROVIDER,
                event,
                new String(rawPayload, StandardCharsets.UTF_8)
        );
        WebhookReceipt receipt = new WebhookReceipt(
                event.eventId(),
                inserted ? "RECEIVED" : "DUPLICATE"
        );
        return inserted ? ResponseEntity.accepted().body(receipt) : ResponseEntity.ok(receipt);
    }

    private BankWebhookEvent parseAndValidate(byte[] rawPayload) {
        BankWebhookEvent event;
        try {
            event = objectMapper.readValue(rawPayload, BankWebhookEvent.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Webhook payload is invalid", exception);
        }
        if (event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 255) {
            throw new IllegalArgumentException("Webhook event ID must contain 1 to 255 characters");
        }
        if (event.paymentId() == null || event.outcome() == null || event.amountMinor() <= 0) {
            throw new IllegalArgumentException("Webhook payment, outcome, and positive amount are required");
        }
        if (event.currency() == null || event.currency().length() != 3) {
            throw new IllegalArgumentException("Webhook currency must be a three-letter ISO code");
        }
        Currency.getInstance(event.currency().toUpperCase(java.util.Locale.ROOT));
        return event;
    }

    public record WebhookReceipt(String eventId, String status) {
    }
}
