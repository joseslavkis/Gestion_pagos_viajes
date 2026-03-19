package com.agencia.pagos.dtos.request;

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
        @NotNull @FutureOrPresent(message = "firstDueDate must be today or in the future") LocalDate firstDueDate
) {}
