package com.agencia.pagos.user.dto;

import com.agencia.pagos.user.Role;

public record AdminUserSearchResultDTO(
        Long id,
        String email,
        String name,
        String lastname,
        String dni,
        String phone,
        Role role,
        Integer studentsCount
) {
}
