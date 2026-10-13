package com.portfolio.paymentgateway.adapter.out.messaging;

import com.portfolio.paymentgateway.application.service.AsyncPaymentProcessingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledPaymentWorkers {

    private final AsyncPaymentProcessingService processingService;

    public ScheduledPaymentWorkers(AsyncPaymentProcessingService processingService) {
        this.processingService = processingService;
    }

    @Scheduled(fixedDelayString = "${payment-processing.poll-interval:1s}")
    public void publishBankChargeOutbox() {
        processingService.processBankChargeOutbox();
    }

    @Scheduled(fixedDelayString = "${webhook-processing.poll-interval:1s}")
    public void processWebhookInbox() {
        processingService.processWebhookInbox();
    }
}
