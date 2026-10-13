package com.portfolio.paymentgateway.adapter.in.web;

import com.portfolio.paymentgateway.application.exception.IdempotencyConflictException;
import com.portfolio.paymentgateway.application.port.in.CreatePaymentUseCase;
import com.portfolio.paymentgateway.application.port.in.GetPaymentUseCase;
import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import com.portfolio.paymentgateway.application.port.out.PaymentRequestRateLimiter;
import com.portfolio.paymentgateway.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final UUID MERCHANT_ID = UUID.fromString("06472856-9c9a-43c9-8b01-1bc745efbce2");
    private static final UUID PAYMENT_ID = UUID.fromString("d88502c7-908e-4a2c-ae5c-91d74285d7c0");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatePaymentUseCase createPayment;

    @MockitoBean
    private GetPaymentUseCase getPayment;

    @MockitoBean
    private PaymentRequestRateLimiter rateLimiter;

    @org.junit.jupiter.api.BeforeEach
    void allowRequestsByDefault() {
        when(rateLimiter.consume(MERCHANT_ID))
                .thenReturn(new PaymentRequestRateLimiter.Decision(true, 60));
    }

    @Test
    void acceptsAValidPaymentAndReturnsLocation() throws Exception {
        when(createPayment.create(eq(MERCHANT_ID), eq("request-1"), any()))
                .thenReturn(response());

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Merchant-Id", MERCHANT_ID)
                        .header("Idempotency-Key", "request-1")
                        .contentType("application/json")
                        .content("""
                                {"amountMinor":1299,"currency":"brl"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/payments/" + PAYMENT_ID))
                .andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectsNonPositiveAmount() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Merchant-Id", MERCHANT_ID)
                        .header("Idempotency-Key", "request-1")
                        .contentType("application/json")
                        .content("""
                                {"amountMinor":0,"currency":"BRL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsRequestsWithoutAnIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Merchant-Id", MERCHANT_ID)
                        .contentType("application/json")
                        .content("""
                                {"amountMinor":1299,"currency":"BRL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsConflictWhenIdempotencyKeyIsReusedForAnotherRequest() throws Exception {
        doThrow(new IdempotencyConflictException())
                .when(createPayment).create(eq(MERCHANT_ID), eq("request-1"), any());

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Merchant-Id", MERCHANT_ID)
                        .header("Idempotency-Key", "request-1")
                        .contentType("application/json")
                        .content("""
                                {"amountMinor":1299,"currency":"BRL"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsRequestsOverTheRedisBackedMerchantLimit() throws Exception {
        when(rateLimiter.consume(MERCHANT_ID))
                .thenReturn(new PaymentRequestRateLimiter.Decision(false, 17));

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Merchant-Id", MERCHANT_ID)
                        .header("Idempotency-Key", "request-1")
                        .contentType("application/json")
                        .content("""
                                {"amountMinor":1299,"currency":"BRL"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "17"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void scopesPaymentLookupToMerchant() throws Exception {
        when(getPayment.get(MERCHANT_ID, PAYMENT_ID)).thenReturn(Optional.of(response()));

        mockMvc.perform(get("/api/v1/payments/" + PAYMENT_ID)
                        .header("X-Merchant-Id", MERCHANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(MERCHANT_ID.toString()));

        verify(getPayment).get(MERCHANT_ID, PAYMENT_ID);
    }

    private PaymentResponse response() {
        return new PaymentResponse(PAYMENT_ID, MERCHANT_ID, 1299, "BRL",
                PaymentStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"));
    }
}
