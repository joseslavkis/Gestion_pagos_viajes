package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;
import java.util.List;

public record UserAssignBulkDTO(
        @NotEmpty
        @UniqueElements(message = "Los DNIs no deben repetirse")
        @Size(max = 500, message = "Cannot assign more than 500 students at once")
        List<@Pattern(
                regexp = "^(?=.*\\d)[\\d.\\-\\s]{7,14}$",
                message = "Student DNI must have 7 or 8 digits (dots, dashes, and spaces are allowed)"
        ) String> studentDnis
) {}
