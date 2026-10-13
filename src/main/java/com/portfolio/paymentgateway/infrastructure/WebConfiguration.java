package com.portfolio.paymentgateway.infrastructure;

import com.portfolio.paymentgateway.adapter.in.web.PaymentRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final PaymentRateLimitInterceptor rateLimitInterceptor;

    public WebConfiguration(PaymentRateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/payments")
                .addPathPatterns("/api/v1/payments/**")
                .excludePathPatterns("/api/v1/webhooks/**");
    }
}
