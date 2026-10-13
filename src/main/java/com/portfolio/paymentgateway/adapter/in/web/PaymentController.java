package com.portfolio.paymentgateway.adapter.in.web;

import com.portfolio.paymentgateway.application.port.in.CreatePaymentUseCase;
import com.portfolio.paymentgateway.application.port.in.GetPaymentUseCase;
import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;
import com.portfolio.paymentgateway.application.port.in.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final CreatePaymentUseCase createPayment;
    private final GetPaymentUseCase getPayment;

    public PaymentController(CreatePaymentUseCase createPayment, GetPaymentUseCase getPayment) {
        this.createPayment = createPayment;
        this.getPayment = getPayment;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        var command = new CreatePaymentCommand(
                request.amountMinor(),
                Currency.getInstance(request.currency().toUpperCase(Locale.ROOT))
        );
        PaymentResponse response = createPayment.create(merchantId, idempotencyKey, command);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/payments/" + response.id()))
                .body(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> get(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @PathVariable UUID paymentId
    ) {
        return getPayment.get(merchantId, paymentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
