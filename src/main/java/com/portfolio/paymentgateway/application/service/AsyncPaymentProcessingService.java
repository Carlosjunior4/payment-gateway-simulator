package com.portfolio.paymentgateway.application.service;

import com.portfolio.paymentgateway.application.port.out.AsyncPaymentProcessingStore;
import com.portfolio.paymentgateway.application.port.out.BankChargePort;
import com.portfolio.paymentgateway.application.port.out.BankWebhookDeliveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

public class AsyncPaymentProcessingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncPaymentProcessingService.class);
    private static final int MAX_ATTEMPTS = 6;
    private static final int BATCH_SIZE = 20;

    private final AsyncPaymentProcessingStore store;
    private final BankChargePort bankCharge;
    private final BankWebhookDeliveryPort webhookDelivery;
    private final Clock clock;

    public AsyncPaymentProcessingService(
            AsyncPaymentProcessingStore store,
            BankChargePort bankCharge,
            BankWebhookDeliveryPort webhookDelivery,
            Clock clock
    ) {
        this.store = store;
        this.bankCharge = bankCharge;
        this.webhookDelivery = webhookDelivery;
        this.clock = clock;
    }

    public void processBankChargeOutbox() {
        for (AsyncPaymentProcessingStore.PendingBankCharge charge : store.claimPendingBankCharges(BATCH_SIZE)) {
            process(charge);
        }
    }

    public void processWebhookInbox() {
        for (AsyncPaymentProcessingStore.PendingWebhook webhook : store.claimPendingWebhooks(BATCH_SIZE)) {
            try {
                store.processWebhook(webhook);
            } catch (RuntimeException exception) {
                LOGGER.warn("Webhook processing failed for event {}", webhook.externalEventId(), exception);
                store.retryWebhook(
                        webhook,
                        safeMessage(exception),
                        webhook.attempt() >= MAX_ATTEMPTS,
                        clock.instant().plusMillis(backoffMillis(webhook.attempt()))
                );
            }
        }
    }

    private void process(AsyncPaymentProcessingStore.PendingBankCharge charge) {
        try {
            store.recordBankChargeAttempt(charge);
            var event = bankCharge.charge(
                    charge.paymentId(),
                    charge.merchantId(),
                    charge.command()
            );
            webhookDelivery.deliver(event);
            store.completeBankCharge(charge);
        } catch (RuntimeException exception) {
            LOGGER.warn("Bank charge attempt {} failed for payment {}",
                    charge.attempt(), charge.paymentId(), exception);
            store.retryBankCharge(
                    charge,
                    safeMessage(exception),
                    charge.attempt() >= MAX_ATTEMPTS,
                    clock.instant().plusMillis(backoffMillis(charge.attempt()))
            );
        }
    }

    static long backoffMillis(int attempt) {
        long exponential = Math.min(300_000L, 1_000L << Math.min(Math.max(attempt - 1, 0), 8));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, exponential / 2));
        return exponential / 2 + jitter;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
