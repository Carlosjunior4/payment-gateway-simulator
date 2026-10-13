package com.portfolio.paymentgateway.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class CircuitBreaker {

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean();
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private volatile Instant openedAt;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("Failure threshold must be positive");
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("Open duration must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public <T> T execute(Supplier<T> operation) {
        boolean probe = acquirePermission();
        try {
            T result = operation.get();
            onSuccess(probe);
            return result;
        } catch (RuntimeException exception) {
            onFailure(probe);
            throw exception;
        } finally {
            if (probe) {
                halfOpenProbeInFlight.set(false);
            }
        }
    }

    private boolean acquirePermission() {
        while (true) {
            State current = state.get();
            if (current == State.CLOSED) {
                return false;
            }
            if (current == State.OPEN) {
                if (clock.instant().isBefore(openedAt.plus(openDuration))) {
                    throw new CircuitOpenException();
                }
                if (!state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    continue;
                }
            }
            if (halfOpenProbeInFlight.compareAndSet(false, true)) {
                return true;
            }
            throw new CircuitOpenException();
        }
    }

    private void onSuccess(boolean probe) {
        if (probe) {
            consecutiveFailures.set(0);
            state.compareAndSet(State.HALF_OPEN, State.CLOSED);
        } else if (state.get() == State.CLOSED) {
            consecutiveFailures.set(0);
        }
    }

    private void onFailure(boolean probe) {
        if (probe) {
            openedAt = clock.instant();
            state.set(State.OPEN);
            return;
        }
        if (state.get() == State.CLOSED && consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt = clock.instant();
            state.compareAndSet(State.CLOSED, State.OPEN);
        }
    }

    State state() {
        return state.get();
    }

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public static final class CircuitOpenException extends RuntimeException {

        public CircuitOpenException() {
            super("Bank simulator circuit is open");
        }
    }
}
