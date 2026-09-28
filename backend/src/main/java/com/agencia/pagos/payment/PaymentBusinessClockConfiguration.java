package com.agencia.pagos.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PaymentBusinessClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "paymentBusinessClock")
    Clock paymentBusinessClock() {
        return Clock.systemUTC();
    }
}
