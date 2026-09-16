package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TripDetailDTO(
        Long id,
        String name,
        BigDecimal totalAmount,
        BigDecimal firstInstallmentAmount,
        Integer installmentsCount,
        Integer dueDay,
        Integer yellowWarningDays,
        Boolean retroactiveActive,
        Currency currency,
        LocalDate firstDueDate,
        Integer assignedUsersCount,
        Integer assignedParticipantsCount
) {
        // Rollout compatibility shim (temporary):
        // legacy clients expect this property on the response even though the
        // fine logic has been retired. Exposed only for transport compatibility;
        // it must never be used by the new business code. Remove once all
        // clients are upgraded.
        @JsonProperty("fixedFineAmount")
        public BigDecimal legacyFixedFineAmount() {
                return BigDecimal.ZERO;
        }
}
