package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentBatchPreviewDTO(
        Long anchorInstallmentId,
        Integer installmentsCount,
        Currency tripCurrency,
        Currency paymentCurrency,
        BigDecimal totalReportedAmount,
        BigDecimal exchangeRate,
        BigDecimal totalAmountInTripCurrency,
        LocalDate reportedPaymentDate,
        List<PaymentBatchInstallmentDTO> installments
) {
}
