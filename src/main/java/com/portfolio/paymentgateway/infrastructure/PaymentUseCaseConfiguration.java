package com.portfolio.paymentgateway.infrastructure;

import com.portfolio.paymentgateway.application.port.out.AsyncPaymentProcessingStore;
import com.portfolio.paymentgateway.application.port.out.BankChargePort;
import com.portfolio.paymentgateway.application.port.out.BankWebhookDeliveryPort;
import com.portfolio.paymentgateway.application.port.out.IdempotentPaymentStore;
import com.portfolio.paymentgateway.application.service.AsyncPaymentProcessingService;
import com.portfolio.paymentgateway.application.service.CreatePaymentService;
import com.portfolio.paymentgateway.application.service.GetPaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PaymentUseCaseConfiguration {

    @Bean
    CreatePaymentService createPaymentService(IdempotentPaymentStore paymentStore) {
        return new CreatePaymentService(paymentStore);
    }

    @Bean
    GetPaymentService getPaymentService(IdempotentPaymentStore paymentStore) {
        return new GetPaymentService(paymentStore);
    }

    @Bean
    AsyncPaymentProcessingService asyncPaymentProcessingService(
            AsyncPaymentProcessingStore store,
            BankChargePort bankCharge,
            BankWebhookDeliveryPort webhookDelivery,
            Clock applicationClock
    ) {
        return new AsyncPaymentProcessingService(store, bankCharge, webhookDelivery, applicationClock);
    }
}
