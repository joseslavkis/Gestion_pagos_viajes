package com.agencia.pagos.payment;

import java.time.LocalDate;

public interface ExchangeRateQuoteProvider {

    ExchangeRateQuote getOfficialQuoteForDate(LocalDate requestedDate);
}
