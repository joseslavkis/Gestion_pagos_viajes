package com.agencia.pagos.trip.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpreadsheetReceiptRowDTO(
        Integer installmentNumber,
        LocalDate installmentDueDate,
        String studentLastname,
        String studentName,
        String studentDni,
        LocalDate reportedPaymentDate,
        String paymentMethod,
        BigDecimal reportedAmount,
        String paymentCurrency,
        BigDecimal exchangeRate,
        BigDecimal amountInTripCurrency,
        String status,
        String adminObservation,
        BigDecimal originalReportedAmount,
        String originalPaymentCurrency,
        BigDecimal originalExchangeRate,
        LocalDate quoteRequestedDate,
        LocalDate quoteEffectiveDate,
        String quoteSource,
        String quoteProvider,
        String quoteProviderTimestamp,
        String calculationVersion
) {
    /** Legacy receipt rows have one currency domain and no complete quote audit. */
    public SpreadsheetReceiptRowDTO(Integer installmentNumber, LocalDate installmentDueDate,
            String studentLastname, String studentName, String studentDni, LocalDate reportedPaymentDate,
            String paymentMethod, BigDecimal reportedAmount, String paymentCurrency, BigDecimal exchangeRate,
            BigDecimal amountInTripCurrency, String status, String adminObservation) {
        this(installmentNumber, installmentDueDate, studentLastname, studentName, studentDni,
                reportedPaymentDate, paymentMethod, reportedAmount, paymentCurrency, exchangeRate,
                amountInTripCurrency, status, adminObservation, reportedAmount, paymentCurrency,
                exchangeRate, null, null, null, null, null, null);
    }
}
