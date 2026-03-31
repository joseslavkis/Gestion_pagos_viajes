package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record RegisterPaymentDTO(
        @NotNull Long anchorInstallmentId,
        @NotNull @Positive Integer installmentsCount,
        @NotNull LocalDate reportedPaymentDate,
        @NotNull Currency paymentCurrency,
        @NotNull PaymentMethod paymentMethod,
        @NotNull Long bankAccountId
) {
        public RegisterPaymentDTO(
                        Long anchorInstallmentId,
                        Integer installmentsCount,
                        LocalDate reportedPaymentDate,
                        PaymentMethod paymentMethod,
                        Long bankAccountId
        ) {
                this(anchorInstallmentId, installmentsCount, reportedPaymentDate, Currency.ARS, paymentMethod, bankAccountId);
        }

        public RegisterPaymentDTO(
                        Long anchorInstallmentId,
                        Integer installmentsCount,
                        LocalDate reportedPaymentDate,
                        PaymentMethod paymentMethod
        ) {
                this(anchorInstallmentId, installmentsCount, reportedPaymentDate, Currency.ARS, paymentMethod, null);
        }
}
