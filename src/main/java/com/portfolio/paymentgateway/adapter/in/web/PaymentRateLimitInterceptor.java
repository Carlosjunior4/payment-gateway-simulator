package com.portfolio.paymentgateway.adapter.in.web;

import com.portfolio.paymentgateway.application.port.out.PaymentRequestRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class PaymentRateLimitInterceptor implements HandlerInterceptor {

    private final PaymentRequestRateLimiter rateLimiter;

    public PaymentRateLimitInterceptor(PaymentRequestRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            Object handler
    ) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String merchantHeader = request.getHeader("X-Merchant-Id");
        if (merchantHeader == null) {
            return true;
        }
        UUID merchantId;
        try {
            merchantId = UUID.fromString(merchantHeader);
        } catch (IllegalArgumentException exception) {
            return true;
        }

        PaymentRequestRateLimiter.Decision decision = rateLimiter.consume(merchantId);
        if (decision.allowed()) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Integer.toString(decision.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        try {
            response.getWriter().write("""
                    {"title":"Too Many Requests","status":429,"detail":"Payment request rate limit exceeded","code":"RATE_LIMIT_EXCEEDED"}
                    """.strip());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Rate-limit response could not be written", exception);
        }
        return false;
    }
}
