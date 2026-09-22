package com.agencia.pagos.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicExchangeRateTestConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void paymentFxTestProfile_providesDeterministicCaseFAndCaseGQuotes() throws Exception {
        Path callLog = tempDir.resolve("fx-calls.log");

        try (AnnotationConfigApplicationContext context = testContext(callLog)) {
            ExchangeRateQuoteProvider provider = context.getBean(ExchangeRateQuoteProvider.class);

            ExchangeRateQuote caseF = provider.getOfficialQuoteForDate(LocalDate.of(2026, 1, 15));
            ExchangeRateQuote caseG = provider.getOfficialQuoteForDate(LocalDate.of(2026, 1, 13));

            assertThat(caseF.sellRate()).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(caseG.sellRate()).isEqualByComparingTo(new BigDecimal("1015.50"));
            assertThat(caseG.sellRate().scale()).isEqualTo(2);
            assertThat(caseF.source()).isEqualTo("payment-fx-test");
            assertThat(caseF.provider()).isEqualTo("deterministic-local-provider");
            assertThat(Files.readAllLines(callLog)).containsExactly("2026-01-15", "2026-01-13");
        }
    }

    @Test
    void anyOtherProfile_cannotActivateDeterministicProvider() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.register(DeterministicExchangeRateTestConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(ExchangeRateQuoteProvider.class)).isEmpty();
        }
    }

    private AnnotationConfigApplicationContext testContext(Path callLog) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("payment-fx-test");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "deterministic-fx-test",
                Map.of("payment.fx-test.call-log", callLog.toString())
        ));
        context.register(DeterministicExchangeRateTestConfiguration.class);
        context.refresh();
        return context;
    }
}
