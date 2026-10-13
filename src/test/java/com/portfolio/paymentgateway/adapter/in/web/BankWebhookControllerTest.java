package com.portfolio.paymentgateway.adapter.in.web;

import com.portfolio.paymentgateway.application.port.out.AsyncPaymentProcessingStore;
import com.portfolio.paymentgateway.application.port.out.PaymentRequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BankWebhookController.class)
class BankWebhookControllerTest {

    private static final byte[] VALID_PAYLOAD = """
            {"eventId":"evt-1","paymentId":"d88502c7-908e-4a2c-ae5c-91d74285d7c0","amountMinor":1299,"currency":"BRL","outcome":"AUTHORIZED","bankReference":"bank-ref-1","failureCode":null}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookSignatureVerifier signatureVerifier;

    @MockitoBean
    private AsyncPaymentProcessingStore processingStore;

    @MockitoBean
    private PaymentRequestRateLimiter rateLimiter;

    @Test
    void acknowledgesAndPersistsVerifiedWebhook() throws Exception {
        when(signatureVerifier.isValid(eq("valid-signature"), any())).thenReturn(true);
        when(processingStore.receiveWebhook(eq("fake-bank"), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/fake-bank")
                        .header("X-Bank-Signature", "valid-signature")
                        .contentType("application/json")
                        .content(VALID_PAYLOAD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.eventId").value("evt-1"));

        verify(processingStore).receiveWebhook(eq("fake-bank"), any(), any());
    }

    @Test
    void rejectsInvalidSignatureBeforePersistingPayload() throws Exception {
        when(signatureVerifier.isValid(eq("invalid-signature"), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/webhooks/fake-bank")
                        .header("X-Bank-Signature", "invalid-signature")
                        .contentType("application/json")
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_BANK_SIGNATURE"));
    }

    @Test
    void acknowledgesDuplicateEventWithoutEnqueuingItAgain() throws Exception {
        when(signatureVerifier.isValid(eq("valid-signature"), any())).thenReturn(true);
        when(processingStore.receiveWebhook(eq("fake-bank"), any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/webhooks/fake-bank")
                        .header("X-Bank-Signature", "valid-signature")
                        .contentType("application/json")
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }
}
