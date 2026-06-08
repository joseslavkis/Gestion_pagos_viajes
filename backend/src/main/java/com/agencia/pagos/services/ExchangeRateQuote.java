package com.agencia.pagos.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record ExchangeRateQuote(
        BigDecimal sellRate,
        LocalDate requestedDate,
        LocalDate effectiveDate,
        String source,
        String provider,
        String providerTimestamp
) {
    public ExchangeRateQuote {
        Objects.requireNonNull(sellRate, "sellRate");
        Objects.requireNonNull(requestedDate, "requestedDate");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(source, "source");
        provider = provider == null ? source : provider;
        if (providerTimestamp == null) {
            providerTimestamp = "";
        }
    }
}
