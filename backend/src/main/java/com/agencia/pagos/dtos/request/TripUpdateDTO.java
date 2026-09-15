package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record TripUpdateDTO(
        @Size(min = 2, max = 100) String name,
        @Min(1) @Max(31) Integer dueDay,
        @Min(0) @Max(30) Integer yellowWarningDays,
        Boolean retroactiveActive,
        LocalDate firstDueDate
) {}
