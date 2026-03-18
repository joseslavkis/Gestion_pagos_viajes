package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TripUpdateDTO(
        @Size(min = 2, max = 100) String name,
        @Min(1) @Max(31) Integer dueDay,
        @Min(0) @Max(30) Integer yellowWarningDays,
        @PositiveOrZero BigDecimal fixedFineAmount,
        Boolean retroactiveActive,
        LocalDate firstDueDate
) {}
