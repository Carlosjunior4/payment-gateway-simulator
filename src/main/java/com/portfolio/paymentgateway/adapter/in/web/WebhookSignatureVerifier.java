package com.portfolio.paymentgateway.adapter.in.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class WebhookSignatureVerifier {

    private final byte[] secret;

    public WebhookSignatureVerifier(@Value("${bank.webhook.secret:local-only-change-me}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("Bank webhook secret must contain at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String signature, byte[] payload) {
        if (signature == null || payload == null) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(sign(payload), provided);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }
}
