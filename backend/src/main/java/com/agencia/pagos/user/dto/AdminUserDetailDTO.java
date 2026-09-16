package com.agencia.pagos.user.dto;

import com.agencia.pagos.user.Role;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;

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
