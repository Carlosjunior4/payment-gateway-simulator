package com.portfolio.paymentgateway.adapter.out.bank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;
import com.portfolio.paymentgateway.application.port.out.BankWebhookDeliveryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Component
public class HttpBankWebhookDeliveryAdapter implements BankWebhookDeliveryPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String webhookUrl;
    private final byte[] signingSecret;

    public HttpBankWebhookDeliveryAdapter(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${bank.webhook.url:http://localhost:8080/api/v1/webhooks/fake-bank}") String webhookUrl,
            @Value("${bank.webhook.secret:local-only-change-me}") String signingSecret
    ) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.webhookUrl = webhookUrl;
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        if (signingSecret.length() < 16) {
            throw new IllegalArgumentException("Bank webhook secret must contain at least 16 characters");
        }
    }

    @Override
    public void deliver(BankWebhookEvent event) {
        String payload = serialize(event);
        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bank-Signature", sign(payload))
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private String serialize(BankWebhookEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Bank webhook could not be serialized", exception);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }
}
