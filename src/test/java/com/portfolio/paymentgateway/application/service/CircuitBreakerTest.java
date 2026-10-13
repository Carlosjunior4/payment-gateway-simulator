package com.portfolio.paymentgateway.application.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CircuitBreakerTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void opensAfterConfiguredFailuresAndRejectsCallsUntilResetTimeout() {
        MutableClock clock = new MutableClock(START);
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(10), clock);

        assertThrows(IllegalStateException.class, () -> breaker.execute(() -> {
            throw new IllegalStateException("unavailable");
        }));
        assertThrows(IllegalStateException.class, () -> breaker.execute(() -> {
            throw new IllegalStateException("unavailable");
        }));
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertThrows(CircuitBreaker.CircuitOpenException.class, () -> breaker.execute(() -> "not called"));

        clock.set(START.plusSeconds(11));
        assertEquals("recovered", breaker.execute(() -> "recovered"));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void rejectsInvalidThresholdAndTimeout() {
        Clock clock = Clock.systemUTC();

        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreaker(0, Duration.ofSeconds(1), clock));
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreaker(1, Duration.ZERO, clock));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        private void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
