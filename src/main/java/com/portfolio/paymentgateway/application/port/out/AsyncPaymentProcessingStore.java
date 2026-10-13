package com.portfolio.paymentgateway.application.port.out;

import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;
import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

public interface AsyncPaymentProcessingStore {

    List<PendingBankCharge> claimPendingBankCharges(int limit);

    void recordBankChargeAttempt(PendingBankCharge charge);

    void completeBankCharge(PendingBankCharge charge);

    void retryBankCharge(PendingBankCharge charge, String error, boolean terminal, Instant nextAttemptAt);

    boolean receiveWebhook(String provider, BankWebhookEvent event, String payload);

    List<PendingWebhook> claimPendingWebhooks(int limit);

    void processWebhook(PendingWebhook webhook);

    void retryWebhook(PendingWebhook webhook, String error, boolean terminal, Instant nextAttemptAt);

    record PendingBankCharge(
            UUID outboxId,
            UUID paymentId,
            UUID merchantId,
            long amountMinor,
            String currency,
            int attempt
    ) {
        public CreatePaymentCommand command() {
            return new CreatePaymentCommand(amountMinor, java.util.Currency.getInstance(currency));
        }
    }

    record PendingWebhook(
            UUID inboxId,
            String provider,
            String externalEventId,
            UUID paymentId,
            long amountMinor,
            String currency,
            BankWebhookEvent.Outcome outcome,
            String bankReference,
            String failureCode,
            int attempt
    ) {
        public BankWebhookEvent event() {
            return new BankWebhookEvent(
                    externalEventId,
                    paymentId,
                    amountMinor,
                    currency,
                    outcome,
                    bankReference,
                    failureCode
            );
        }
    }
}
