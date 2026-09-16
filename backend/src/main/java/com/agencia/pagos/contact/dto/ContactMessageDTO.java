package com.agencia.pagos.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ContactMessageDTO(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank String message
) {}
