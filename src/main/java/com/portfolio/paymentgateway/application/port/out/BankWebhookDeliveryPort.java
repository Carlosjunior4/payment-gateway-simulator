package com.portfolio.paymentgateway.application.port.out;

import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;

public interface BankWebhookDeliveryPort {

    void deliver(BankWebhookEvent event);
}
