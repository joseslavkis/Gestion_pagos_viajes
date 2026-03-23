package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.Currency;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TripCreateDTO(
        @NotNull @Size(min = 2, max = 100) String name,
        @NotNull @Positive BigDecimal totalAmount,
        @NotNull @Min(1) @Max(60) Integer installmentsCount,
        @NotNull @Min(1) @Max(31) Integer dueDay,
        @NotNull @Min(0) @Max(30) Integer yellowWarningDays,
        @NotNull @PositiveOrZero BigDecimal fixedFineAmount,
        @NotNull Boolean retroactiveActive,
        @NotNull Currency currency,
        @NotNull LocalDate firstDueDate
) {
        public TripCreateDTO(
                        String name,
                        BigDecimal totalAmount,
                        Integer installmentsCount,
                        Integer dueDay,
                        Integer yellowWarningDays,
                        BigDecimal fixedFineAmount,
                        Boolean retroactiveActive,
                        LocalDate firstDueDate
        ) {
                this(name, totalAmount, installmentsCount, dueDay, yellowWarningDays, fixedFineAmount, retroactiveActive, Currency.ARS, firstDueDate);
        }
}
