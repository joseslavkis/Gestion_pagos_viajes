package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RegisterPaymentDTO(
        @NotNull Long installmentId,
        @NotNull @Positive BigDecimal reportedAmount,
        @NotNull LocalDate reportedPaymentDate,
        @NotNull Currency paymentCurrency,
        @NotNull PaymentMethod paymentMethod,
        @NotNull Long bankAccountId
) {
        public RegisterPaymentDTO(
                        Long installmentId,
                        BigDecimal reportedAmount,
                        LocalDate reportedPaymentDate,
                        PaymentMethod paymentMethod,
                        Long bankAccountId
        ) {
                this(installmentId, reportedAmount, reportedPaymentDate, Currency.ARS, paymentMethod, bankAccountId);
        }

        public RegisterPaymentDTO(
                        Long installmentId,
                        BigDecimal reportedAmount,
                        LocalDate reportedPaymentDate,
                        PaymentMethod paymentMethod
        ) {
                this(installmentId, reportedAmount, reportedPaymentDate, Currency.ARS, paymentMethod, null);
        }
}
