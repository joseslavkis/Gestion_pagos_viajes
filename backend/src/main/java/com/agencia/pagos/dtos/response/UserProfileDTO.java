package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Role;

public record UserProfileDTO(
        Long id,
        String email,
        String name,
        String lastname,
        Role role
) {}