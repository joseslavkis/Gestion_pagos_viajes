package com.agencia.pagos.payment;

import com.agencia.pagos.shared.money.Currency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Persisted evidence, including authentic nullable legacy metadata; not a live quote. */
public record PaymentOutcomeSnapshot(
        Currency currency, BigDecimal exchangeRate, Integer exchangeRateScale,
        LocalDate requestedDate, LocalDate effectiveDate, String source,
        String provider, String providerTimestamp, String calculationVersion) {

    public PaymentOutcomeSnapshot {
        Objects.requireNonNull(currency, "currency");
    }

    public static PaymentOutcomeSnapshot fromSubmission(PaymentSubmission submission) {
        return new PaymentOutcomeSnapshot(submission.getPaymentCurrency(), submission.getExchangeRate(),
                submission.getExchangeRateScale(), submission.getExchangeRateRequestedDate(),
                submission.getExchangeRateEffectiveDate(), submission.getExchangeRateSource(),
                submission.getExchangeRateProvider(), submission.getExchangeRateProviderTimestamp(),
                submission.getCalculationVersion());
    }

    public static PaymentOutcomeSnapshot fromOutcome(PaymentOutcome outcome) {
        return new PaymentOutcomeSnapshot(outcome.getCurrency(), outcome.getExchangeRate(),
                outcome.getExchangeRateScale(), outcome.getExchangeRateRequestedDate(),
                outcome.getExchangeRateEffectiveDate(), outcome.getExchangeRateSource(),
                outcome.getExchangeRateProvider(), outcome.getExchangeRateProviderTimestamp(),
                outcome.getCalculationVersion());
    }

    public static PaymentOutcomeSnapshot administrative(Currency currency, ExchangeRateQuote quote) {
        return new PaymentOutcomeSnapshot(currency, quote == null ? null : quote.sellRate(),
                quote == null ? null : quote.sellRate().scale(), quote == null ? null : quote.requestedDate(),
                quote == null ? null : quote.effectiveDate(), quote == null ? null : quote.source(),
                quote == null ? null : quote.provider(), quote == null ? null : quote.providerTimestamp(),
                PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION);
    }

    public BigDecimal requireApplicableRate(PaymentMoneyPolicy money, LocalDate paymentDate) {
        try {
            BigDecimal rate = money.requireProviderRate(exchangeRate);
            if (!"v1".equals(calculationVersion)) {
                if (exchangeRateScale == null || exchangeRateScale < 0
                        || exchangeRateScale > PaymentMoneyPolicy.RATE_SCALE_LIMIT
                        || !Objects.equals(paymentDate, requestedDate) || effectiveDate == null
                        || effectiveDate.isAfter(paymentDate) || source == null || source.isBlank()
                        || provider == null || provider.isBlank()) {
                    throw new IllegalStateException("Incomplete frozen quote evidence");
                }
                rate = rate.setScale(exchangeRateScale, java.math.RoundingMode.UNNECESSARY);
            }
            return rate;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(AdministrativeReviewQuoteResolver.RECONCILIATION_REQUIRED, exception);
        }
    }
}
