package com.portfolio.paymentgateway.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentgateway.application.exception.IdempotencyConflictException;
import com.portfolio.paymentgateway.application.exception.MerchantNotActiveException;
import com.portfolio.paymentgateway.application.exception.MerchantNotFoundException;
import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import com.portfolio.paymentgateway.application.port.out.IdempotentPaymentStore;
import com.portfolio.paymentgateway.domain.model.Payment;
import com.portfolio.paymentgateway.domain.model.PaymentStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcPaymentStore implements IdempotentPaymentStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPaymentStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PaymentResponse createOrReplay(
            UUID merchantId,
            String idempotencyKey,
            String requestHash,
            Payment payment,
            PaymentResponse initialResponse
    ) {
        String merchantStatus;
        try {
            merchantStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM merchants WHERE id = ?",
                    String.class,
                    merchantId
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new MerchantNotFoundException(merchantId);
        }
        if (!"ACTIVE".equals(merchantStatus)) {
            throw new MerchantNotActiveException(merchantId);
        }

        int inserted = jdbcTemplate.update("""
                        INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash)
                        VALUES (?, ?, ?)
                        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                        """,
                merchantId,
                idempotencyKey,
                requestHash
        );

        if (inserted == 0) {
            return replayExisting(merchantId, idempotencyKey, requestHash);
        }

        jdbcTemplate.update("""
                        INSERT INTO payments (
                            id, merchant_id, amount_minor, currency, status, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                payment.id(),
                payment.merchantId(),
                payment.amount().amountMinor(),
                payment.amount().currency().getCurrencyCode(),
                payment.status().name(),
                payment.createdAt(),
                payment.updatedAt()
        );

        String responseJson = serialize(initialResponse);
        int updated = jdbcTemplate.update("""
                        UPDATE idempotency_keys
                        SET payment_id = ?, response_status = 202, response_body = ?::jsonb
                        WHERE merchant_id = ? AND idempotency_key = ?
                        """,
                payment.id(),
                responseJson,
                merchantId,
                idempotencyKey
        );
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record could not be completed");
        }
        jdbcTemplate.update("""
                        INSERT INTO outbox_events (
                            id, aggregate_type, aggregate_id, event_type, payload, status
                        ) VALUES (?, 'PAYMENT', ?, 'PAYMENT_SUBMITTED', ?::jsonb, 'PENDING')
                        """,
                UUID.randomUUID(),
                payment.id(),
                serialize(new BankChargeMessage(
                        payment.id(),
                        payment.merchantId(),
                        payment.amount().amountMinor(),
                        payment.amount().currency().getCurrencyCode()
                ))
        );
        return initialResponse;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findByMerchantAndPaymentId(UUID merchantId, UUID paymentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                            SELECT id, merchant_id, amount_minor, currency, status, created_at
                            FROM payments
                            WHERE merchant_id = ? AND id = ?
                            """,
                    (resultSet, rowNumber) -> mapPayment(resultSet),
                    merchantId,
                    paymentId
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private PaymentResponse replayExisting(UUID merchantId, String key, String requestHash) {
        StoredIdempotency stored = jdbcTemplate.queryForObject("""
                        SELECT request_hash, response_body::text AS response_body
                        FROM idempotency_keys
                        WHERE merchant_id = ? AND idempotency_key = ?
                        """,
                (resultSet, rowNumber) -> new StoredIdempotency(
                        resultSet.getString("request_hash"),
                        resultSet.getString("response_body")
                ),
                merchantId,
                key
        );

        if (!requestHash.equals(stored.requestHash())) {
            throw new IdempotencyConflictException();
        }
        if (stored.responseBody() == null) {
            throw new IllegalStateException("Committed idempotency record has no response body");
        }
        try {
            return objectMapper.readValue(stored.responseBody(), PaymentResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is invalid", exception);
        }
    }

    private String serialize(PaymentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment response could not be serialized", exception);
        }
    }

    private String serialize(BankChargeMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Bank charge message could not be serialized", exception);
        }
    }

    private PaymentResponse mapPayment(ResultSet resultSet) throws SQLException {
        return new PaymentResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("merchant_id", UUID.class),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency").trim(),
                PaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private record StoredIdempotency(String requestHash, String responseBody) {
    }

    private record BankChargeMessage(UUID paymentId, UUID merchantId, long amountMinor, String currency) {
    }
}
