package com.agencia.pagos.dtos.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

// Rollout compatibility shim (temporary):
// legacy clients may still send `fixedFineAmount` in PATCH bodies.
// We explicitly whitelist only that key and do not broaden DTO-level
// unknown-property suppression with ignoreUnknown=true.
@JsonIgnoreProperties(value = {"fixedFineAmount"})
public record TripUpdateDTO(
        @Size(min = 2, max = 100) String name,
        @Min(1) @Max(31) Integer dueDay,
        @Min(0) @Max(30) Integer yellowWarningDays,
        Boolean retroactiveActive,
        LocalDate firstDueDate
) {}
