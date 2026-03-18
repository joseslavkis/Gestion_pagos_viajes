package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.user.UserCredentials;

import jakarta.validation.constraints.NotBlank;

public record UserLoginDTO(
        @NotBlank String email,
        @NotBlank String password
) implements UserCredentials {}