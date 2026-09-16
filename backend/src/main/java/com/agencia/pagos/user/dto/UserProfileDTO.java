package com.agencia.pagos.user.dto;

import com.agencia.pagos.user.Role;

public record UserProfileDTO(
        Long id,
        String email,
        String name,
        String lastname,
        Role role
) {}
