package com.agencia.pagos;

import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.InstallmentUiStatusCode;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.services.InstallmentUiStatus;
import com.agencia.pagos.services.InstallmentUiStatusResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstallmentUiStatusResolverTest {

    private final InstallmentUiStatusResolver resolver = new InstallmentUiStatusResolver();

    @Test
    void greenConApproved_devuelvePaid() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.GREEN,
                ReceiptStatus.APPROVED,
                LocalDate.now().plusDays(10),
                5,
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.PAID, result.code());
        assertEquals("Pagada", result.label());
        assertEquals("green", result.tone());
    }

    @Test
    void greenSinReceipt_devuelvePaid() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.GREEN,
                null,
                LocalDate.now().plusDays(10),
                5,
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.PAID, result.code());
    }

    @Test
    void yellowConPending_devuelveUnderReview() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.YELLOW,
                ReceiptStatus.PENDING,
                LocalDate.now().plusDays(1),
                5,
                BigDecimal.ZERO,
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.UNDER_REVIEW, result.code());
        assertEquals("En revisión", result.label());
    }

    @Test
    void yellowConRejected_devuelveReceiptRejected() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.YELLOW,
                ReceiptStatus.REJECTED,
                LocalDate.now().plusDays(1),
                5,
                BigDecimal.ZERO,
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.RECEIPT_REJECTED, result.code());
    }

    @Test
    void yellowDentroVentana_devuelveDueSoon() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.YELLOW,
                null,
                LocalDate.now().plusDays(2),
                5,
                BigDecimal.ZERO,
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.DUE_SOON, result.code());
    }

    @Test
    void yellowFueraVentana_devuelveUpToDate() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.YELLOW,
                null,
                LocalDate.now().plusDays(20),
                5,
                BigDecimal.ZERO,
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.UP_TO_DATE, result.code());
    }

    @Test
    void red_devuelveOverdue() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.RED,
                null,
                LocalDate.now().minusDays(1),
                5,
                BigDecimal.ZERO,
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.OVERDUE, result.code());
    }

    @Test
    void retroactive_devuelveRetroactiveDebt() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.RETROACTIVE,
                null,
                LocalDate.now().minusDays(20),
                5,
                BigDecimal.ZERO,
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.RETROACTIVE_DEBT, result.code());
    }

    @Test
    void greenConApprovedPeroSaldoParcial_devuelveUnderReviewSiHayPendiente() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.YELLOW,
                ReceiptStatus.PENDING,
                LocalDate.now().plusDays(10),
                5,
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.UNDER_REVIEW, result.code());
    }

    @Test
    void cuotaTotalmenteCubiertaConRejectedSigueViendoPaid() {
        InstallmentUiStatus result = resolver.resolve(
                InstallmentStatus.YELLOW,
                ReceiptStatus.REJECTED,
                LocalDate.now().plusDays(10),
                5,
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(200)
        );

        assertEquals(InstallmentUiStatusCode.PAID, result.code());
    }
}
