package com.portfolio.paymentgateway.adapter.in.web;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "local-test-secret-with-at-least-32-bytes";

    @Test
    void acceptsValidHmacAndRejectsTamperedPayload() throws Exception {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);
        byte[] payload = "{\"eventId\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(payload);

        assertTrue(verifier.isValid(signature, payload));
        assertFalse(verifier.isValid(signature, "{\"eventId\":\"evt-2\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMissingAndMalformedSignatures() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.isValid(null, payload));
        assertFalse(verifier.isValid("not-hex", payload));
        assertFalse(verifier.isValid("00", payload));
    }

    private String sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
