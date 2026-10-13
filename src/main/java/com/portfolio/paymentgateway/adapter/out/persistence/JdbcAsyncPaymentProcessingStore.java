package com.portfolio.paymentgateway.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;
import com.portfolio.paymentgateway.application.port.out.AsyncPaymentProcessingStore;
import com.portfolio.paymentgateway.domain.model.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAsyncPaymentProcessingStore implements AsyncPaymentProcessingStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcAsyncPaymentProcessingStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<PendingBankCharge> claimPendingBankCharges(int limit) {
        return jdbcTemplate.query("""
                        WITH candidates AS (
                            SELECT id
                            FROM outbox_events
                            WHERE (
                                (status IN ('PENDING', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP)
                                OR (status = 'PROCESSING' AND locked_until <= CURRENT_TIMESTAMP)
                            )
                              AND event_type = 'PAYMENT_SUBMITTED'
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT ?
                        )
                        UPDATE outbox_events event
                        SET status = 'PROCESSING', attempts = attempts + 1,
                            locked_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                        FROM candidates
                        WHERE event.id = candidates.id
                        RETURNING event.id, event.aggregate_id, event.payload::text AS payload, event.attempts
                        """,
                (resultSet, rowNumber) -> {
                    BankChargeMessage message = readMessage(resultSet.getString("payload"));
                    return new PendingBankCharge(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("aggregate_id", UUID.class),
                            message.merchantId(),
                            message.amountMinor(),
                            message.currency(),
                            resultSet.getInt("attempts")
                    );
                },
                limit
        );
    }

    @Override
    @Transactional
    public void recordBankChargeAttempt(PendingBankCharge charge) {
        jdbcTemplate.update("""
                        INSERT INTO payment_attempts (
                            id, payment_id, attempt_number, status, request_metadata, started_at
                        ) VALUES (?, ?, ?, 'SENT', ?::jsonb, ?)
                        """,
                UUID.randomUUID(),
                charge.paymentId(),
                charge.attempt(),
                writeJson(new BankChargeMessage(
                        charge.paymentId(),
                        charge.merchantId(),
                        charge.amountMinor(),
                        charge.currency()
                )),
                toDatabaseTimestamp(clock.instant())
        );
    }

    @Override
    @Transactional
    public void completeBankCharge(PendingBankCharge charge) {
        jdbcTemplate.update("""
                        UPDATE payment_attempts
                        SET status = 'SUCCEEDED', finished_at = ?
                        WHERE payment_id = ? AND attempt_number = ?
                        """,
                toDatabaseTimestamp(clock.instant()),
                charge.paymentId(),
                charge.attempt()
        );
        jdbcTemplate.update("""
                        UPDATE outbox_events
                        SET status = 'PUBLISHED', published_at = ?, last_error = NULL, locked_until = NULL
                        WHERE id = ? AND status = 'PROCESSING'
                        """,
                toDatabaseTimestamp(clock.instant()),
                charge.outboxId()
        );
    }

    @Override
    @Transactional
    public void retryBankCharge(
            PendingBankCharge charge,
            String error,
            boolean terminal,
            Instant nextAttemptAt
    ) {
        jdbcTemplate.update("""
                        UPDATE payment_attempts
                        SET status = 'FAILED', finished_at = ?, response_metadata = ?::jsonb
                        WHERE payment_id = ? AND attempt_number = ?
                        """,
                toDatabaseTimestamp(clock.instant()),
                writeJson(new ErrorMessage(error)),
                charge.paymentId(),
                charge.attempt()
        );
        jdbcTemplate.update("""
                        UPDATE outbox_events
                        SET status = ?, last_error = ?, next_attempt_at = ?, published_at = NULL,
                            locked_until = NULL
                        WHERE id = ? AND status = 'PROCESSING'
                        """,
                terminal ? "DEAD" : "FAILED",
                error,
                toDatabaseTimestamp(nextAttemptAt),
                charge.outboxId()
        );
        if (terminal) {
            jdbcTemplate.update("""
                            UPDATE payments
                            SET status = 'FAILED', failure_code = 'bank_unavailable', updated_at = ?
                            WHERE id = ? AND status = 'PENDING'
                            """,
                    toDatabaseTimestamp(clock.instant()),
                    charge.paymentId()
            );
        }
    }

    @Override
    @Transactional
    public boolean receiveWebhook(String provider, BankWebhookEvent event, String payload) {
        int inserted = jdbcTemplate.update("""
                        INSERT INTO webhook_inbox (
                            id, provider, external_event_id, payment_id, payload, status
                        ) VALUES (?, ?, ?, ?, ?::jsonb, 'RECEIVED')
                        ON CONFLICT (provider, external_event_id) DO NOTHING
                        """,
                UUID.randomUUID(),
                provider,
                event.eventId(),
                event.paymentId(),
                payload
        );
        return inserted == 1;
    }

    @Override
    @Transactional
    public List<PendingWebhook> claimPendingWebhooks(int limit) {
        return jdbcTemplate.query("""
                        WITH candidates AS (
                            SELECT id
                            FROM webhook_inbox
                            WHERE (
                                (status IN ('RECEIVED', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP)
                                OR (status = 'PROCESSING' AND locked_until <= CURRENT_TIMESTAMP)
                            )
                            ORDER BY next_attempt_at, received_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT ?
                        )
                        UPDATE webhook_inbox event
                        SET status = 'PROCESSING', attempts = attempts + 1, last_error = NULL,
                            locked_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                        FROM candidates
                        WHERE event.id = candidates.id
                        RETURNING event.id, event.provider, event.external_event_id,
                            event.payload::text AS payload, event.attempts
                        """,
                (resultSet, rowNumber) -> {
                    BankWebhookEvent event = readWebhook(resultSet.getString("payload"));
                    return new PendingWebhook(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("provider"),
                            resultSet.getString("external_event_id"),
                            event.paymentId(),
                            event.amountMinor(),
                            event.currency(),
                            event.outcome(),
                            event.bankReference(),
                            event.failureCode(),
                            resultSet.getInt("attempts")
                    );
                },
                limit
        );
    }

    @Override
    @Transactional
    public void processWebhook(PendingWebhook webhook) {
        PaymentRow payment = jdbcTemplate.query("""
                            SELECT amount_minor, currency, status
                            FROM payments WHERE id = ? FOR UPDATE
                            """,
                    (resultSet, rowNumber) -> new PaymentRow(
                            resultSet.getLong("amount_minor"),
                            resultSet.getString("currency").trim(),
                            PaymentStatus.valueOf(resultSet.getString("status"))
                    ),
                    webhook.paymentId()
            ).stream().findFirst().orElseThrow(() ->
                    new IllegalStateException("Webhook refers to an unknown payment"));
        if (payment.status().isTerminal()) {
            markWebhookProcessed(webhook.inboxId());
            return;
        }
        if (payment.amountMinor() != webhook.amountMinor()
                || !payment.currency().equalsIgnoreCase(webhook.currency())) {
            throw new IllegalStateException("Webhook amount or currency does not match payment");
        }
        String bankReference = webhook.bankReference();
        String failureCode = webhook.failureCode();
        if (webhook.outcome() == BankWebhookEvent.Outcome.AUTHORIZED) {
            if (bankReference == null || bankReference.isBlank()) {
                throw new IllegalStateException("Authorized webhook has no bank reference");
            }
            jdbcTemplate.update("""
                            UPDATE payments
                            SET status = 'AUTHORIZED', bank_reference = ?, failure_code = NULL, updated_at = ?
                            WHERE id = ? AND status = 'PENDING'
                            """,
                    bankReference,
                    toDatabaseTimestamp(clock.instant()),
                    webhook.paymentId()
            );
        } else {
            if (failureCode == null || failureCode.isBlank()) {
                throw new IllegalStateException("Declined webhook has no failure code");
            }
            jdbcTemplate.update("""
                            UPDATE payments
                            SET status = 'DECLINED', failure_code = ?, updated_at = ?
                            WHERE id = ? AND status = 'PENDING'
                            """,
                    failureCode,
                    toDatabaseTimestamp(clock.instant()),
                    webhook.paymentId()
            );
        }
        markWebhookProcessed(webhook.inboxId());
    }

    @Override
    @Transactional
    public void retryWebhook(
            PendingWebhook webhook,
            String error,
            boolean terminal,
            Instant nextAttemptAt
    ) {
        jdbcTemplate.update("""
                        UPDATE webhook_inbox
                        SET status = ?, last_error = ?, locked_until = NULL, next_attempt_at = ?
                        WHERE id = ? AND status = 'PROCESSING'
                        """,
                terminal ? "DEAD" : "FAILED",
                error,
                toDatabaseTimestamp(nextAttemptAt),
                webhook.inboxId()
        );
    }

    private void markWebhookProcessed(UUID inboxId) {
        jdbcTemplate.update("""
                        UPDATE webhook_inbox
                        SET status = 'PROCESSED', processed_at = ?, last_error = NULL, locked_until = NULL
                        WHERE id = ? AND status = 'PROCESSING'
                        """,
                toDatabaseTimestamp(clock.instant()),
                inboxId
        );
    }

    private BankChargeMessage readMessage(String payload) {
        try {
            return objectMapper.readValue(payload, BankChargeMessage.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Outbox bank charge message is invalid", exception);
        }
    }

    private BankWebhookEvent readWebhook(String payload) {
        try {
            return objectMapper.readValue(payload, BankWebhookEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored bank webhook is invalid", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Message could not be serialized", exception);
        }
    }

    private OffsetDateTime toDatabaseTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record BankChargeMessage(UUID paymentId, UUID merchantId, long amountMinor, String currency) {
    }

    private record ErrorMessage(String message) {
    }

    private record PaymentRow(long amountMinor, String currency, PaymentStatus status) {
    }
}
