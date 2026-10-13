package com.portfolio.paymentgateway.adapter.out.cache;

import com.portfolio.paymentgateway.application.port.out.PaymentRequestRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
public class RedisPaymentRequestRateLimiter implements PaymentRequestRateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final int limit;
    private final int windowSeconds;

    public RedisPaymentRequestRateLimiter(
            StringRedisTemplate redisTemplate,
            Clock clock,
            @Value("${payment-rate-limit.max-requests:60}") int limit,
            @Value("${payment-rate-limit.window:60s}") java.time.Duration window
    ) {
        if (limit < 1 || window == null || window.isNegative() || window.isZero()
                || window.getSeconds() < 1 || window.getSeconds() > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Payment rate-limit configuration is invalid");
        }
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.limit = limit;
        this.windowSeconds = Math.toIntExact(window.getSeconds());
    }

    @Override
    public Decision consume(UUID merchantId) {
        long currentSecond = clock.instant().getEpochSecond();
        long window = Math.floorDiv(currentSecond, windowSeconds);
        long count = increment(merchantId, window);
        int secondsIntoWindow = (int) Math.floorMod(currentSecond, windowSeconds);
        int retryAfter = windowSeconds - secondsIntoWindow;
        return new Decision(count <= limit, retryAfter);
    }

    private long increment(UUID merchantId, long window) {
        String key = "rate-limit:payments:" + merchantId + ":" + window;
        Long count = redisTemplate.execute(
                INCREMENT_WINDOW,
                List.of(key),
                Integer.toString(windowSeconds * 2)
        );
        if (count == null) {
            throw new IllegalStateException("Redis did not return a payment rate-limit counter");
        }
        return count;
    }
}
