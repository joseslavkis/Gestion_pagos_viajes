package com.agencia.pagos.payment;

import com.agencia.pagos.PagosApplication;
import org.springframework.boot.SpringApplication;

public final class PaymentFullStackTestApplication {

    private PaymentFullStackTestApplication() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(
                PagosApplication.class,
                DeterministicExchangeRateTestConfiguration.class
        );
        application.setAdditionalProfiles("payment-fx-test");
        application.run(args);
    }
}
