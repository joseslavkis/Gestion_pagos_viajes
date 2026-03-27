package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Role;

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
