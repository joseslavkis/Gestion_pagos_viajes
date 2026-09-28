package com.agencia.pagos.payment;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.trip.InstallmentUiStatusCode;
import com.agencia.pagos.user.dto.UserInstallmentDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentUserInstallmentDTOTest {

    @Test
    void serializesBackendComputedRemainingBalanceAsCanonicalDecimalString() throws Exception {
        UserInstallmentDTO installment = new UserInstallmentDTO(
                1L,
                "Trip",
                2L,
                "Student",
                "12345678",
                3L,
                1,
                LocalDate.of(2026, 9, 24),
                new BigDecimal("200.00"),
                new BigDecimal("30.01"),
                new BigDecimal("169.99"),
                5,
                Currency.ARS,
                InstallmentStatus.YELLOW,
                null,
                InstallmentUiStatusCode.DUE_SOON,
                "Vence pronto",
                "yellow",
                null,
                false
        );

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(installment);

        assertThat(json).contains("\"remainingAmount\":\"169.99\"");
    }
}
