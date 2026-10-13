package com.portfolio.paymentgateway.infrastructure;

import com.portfolio.paymentgateway.application.service.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ResilienceConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    CircuitBreaker bankCircuitBreaker(
            @Value("${bank.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${bank.circuit-breaker.open-duration:10s}") Duration openDuration,
            Clock applicationClock
    ) {
        return new CircuitBreaker(failureThreshold, openDuration, applicationClock);
    }
}
