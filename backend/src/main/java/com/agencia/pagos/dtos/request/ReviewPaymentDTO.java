package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.ReceiptStatus;
import jakarta.validation.constraints.NotNull;

public record ReviewPaymentDTO(
        @NotNull ReceiptStatus decision,
        String adminObservation
) {
}
