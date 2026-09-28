package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.PaymentCalculationIntent;
import com.agencia.pagos.payment.dto.PaymentCalculationResponseDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationStatus;
import com.agencia.pagos.shared.money.Currency;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCalculationResponseDTOTest {

    @Test
    void responseNamesAnchorBalanceSeparatelyFromEnrollmentTotal() throws Exception {
        PaymentCalculationResponseDTO response = new PaymentCalculationResponseDTO(
                PaymentCalculationStatus.READY,
                PaymentCalculationIntent.REMAINING,
                7L,
                Currency.ARS,
                Currency.USD,
                new BigDecimal("66.66"),
                new BigDecimal("199.98"),
                new BigDecimal("200.00"),
                new BigDecimal("400.00"),
                new BigDecimal("66.66"),
                new BigDecimal("0.02"),
                new BigDecimal("3"),
                LocalDate.of(2026, 9, 23),
                LocalDate.of(2026, 9, 23),
                LocalDate.of(2026, 9, 23),
                "official",
                "provider-a",
                null,
                "3",
                "preview-token",
                List.of(),
                null
        );

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(json)
                .contains("\"anchorRemainingAmount\":\"200.00\"")
                .contains("\"totalPendingAmountInTripCurrency\":\"400.00\"")
                .doesNotContain("\"remainingAmount\":");
    }
}
