package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.PaymentBatchPreviewDTO;
import com.agencia.pagos.payment.dto.PaymentInstallmentHistoryDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.dto.PendingPaymentReviewDTO;
import com.agencia.pagos.payment.dto.PaymentBatchInstallmentDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentSubmissionSnapshotContractTest {

    @Test
    void submissionPersistsCompleteQuoteIdentity() {
        List<String> fields = Arrays.stream(PaymentSubmission.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();

        assertTrue(fields.contains("exchangeRateScale"));
        assertTrue(fields.contains("exchangeRateProvider"));
        assertTrue(fields.contains("calculationVersion"));
    }

    @Test
    void quoteIdentityIsExposedAcrossPreviewReviewAndHistoryDtos() {
        for (Class<?> dto : List.of(
                PaymentBatchPreviewDTO.class,
                PaymentSubmissionDTO.class,
                PendingPaymentReviewDTO.class,
                PaymentInstallmentHistoryDTO.class
        )) {
            List<String> components = Arrays.stream(dto.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertTrue(components.contains("quoteProvider"), dto.getSimpleName());
            assertTrue(components.contains("calculationVersion"), dto.getSimpleName());
        }
    }

    @Test
    void canonicalDecimalSerializerPreservesMoneyScaleAndTrailingZeros() throws Exception {
        PaymentBatchInstallmentDTO dto = new PaymentBatchInstallmentDTO(
                null,
                1L,
                1,
                LocalDate.of(2026, 9, 18),
                new BigDecimal("1015.50"),
                new BigDecimal("0.00"),
                new BigDecimal("1015.50"),
                new BigDecimal("1234.567"),
                new BigDecimal("10.16"),
                null
        );

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(dto);

        var tree = new ObjectMapper().readTree(json);
        assertEquals("1015.50", tree.path("totalDue").asText());
        assertEquals("1234.567", tree.path("reportedAmount").asText());
        assertEquals("10.16", tree.path("amountInTripCurrency").asText());
    }

    @Test
    void calculationDtoUsesExplicitMoneyAndCurrencyFieldSemantics() {
        List<String> components = Arrays.stream(PaymentCalculationResponseDTO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertTrue(components.containsAll(List.of(
                "reportedAmount",
                "amountInTripCurrency",
                "remainingAmount",
                "maxAllowedAmount",
                "tripCurrencyResidual",
                "exchangeRate",
                "paymentCurrency",
                "tripCurrency"
        )));
        assertTrue(!components.contains("amount"));
        assertTrue(!components.contains("currency"));
    }
}
