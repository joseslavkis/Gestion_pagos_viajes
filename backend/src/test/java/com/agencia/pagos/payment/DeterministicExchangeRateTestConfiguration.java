package com.agencia.pagos.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

@TestConfiguration(proxyBeanMethods = false)
@Profile("payment-fx-test")
public class DeterministicExchangeRateTestConfiguration {

    static final LocalDate CASE_F_FAST_DATE = LocalDate.of(2026, 1, 15);
    static final LocalDate CASE_F_SLOW_DATE = LocalDate.of(2026, 1, 14);
    static final LocalDate CASE_G_DATE = LocalDate.of(2026, 1, 13);

    @Bean
    @Primary
    ExchangeRateQuoteProvider deterministicExchangeRateQuoteProvider(
            @Value("${payment.fx-test.call-log:}") String callLog
    ) {
        return requestedDate -> {
            recordCall(callLog, requestedDate);
            if (CASE_F_SLOW_DATE.equals(requestedDate)) {
                sleepForStaleResponseScenario();
            }

            BigDecimal rate = CASE_G_DATE.equals(requestedDate)
                    ? new BigDecimal("1015.50")
                    : new BigDecimal("1234.56");
            return new ExchangeRateQuote(
                    rate,
                    requestedDate,
                    requestedDate,
                    "payment-fx-test",
                    "deterministic-local-provider",
                    requestedDate + "T12:00:00Z"
            );
        };
    }

    private static void recordCall(String callLog, LocalDate requestedDate) {
        if (callLog == null || callLog.isBlank()) {
            return;
        }
        try {
            Files.writeString(
                    Path.of(callLog),
                    requestedDate + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not record deterministic FX test call", exception);
        }
    }

    private static void sleepForStaleResponseScenario() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Deterministic FX test delay was interrupted", exception);
        }
    }
}
