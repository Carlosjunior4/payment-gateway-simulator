package com.portfolio.paymentgateway.application.port.out;

import com.portfolio.paymentgateway.application.port.in.dto.CreatePaymentCommand;
import com.portfolio.paymentgateway.application.port.in.dto.BankWebhookEvent;

import java.util.UUID;

public interface BankChargePort {

    BankWebhookEvent charge(UUID paymentId, UUID merchantId, CreatePaymentCommand command);
}
