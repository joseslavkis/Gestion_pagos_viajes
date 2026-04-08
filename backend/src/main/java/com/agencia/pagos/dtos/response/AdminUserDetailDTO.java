package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Role;

import java.util.List;

public record AdminUserDetailDTO(
        Long id,
        String email,
        String name,
        String lastname,
        String dni,
        String phone,
        Role role,
        List<StudentDTO> students,
        List<AdminUserInstallmentDTO> installments,
        List<PaymentSubmissionDTO> payments
) {
}
