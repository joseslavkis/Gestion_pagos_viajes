package com.agencia.pagos.services;

import com.agencia.pagos.entities.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentPreviewTokenServiceTest {

    private static final String SECRET = "ZmFrZS1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaG1hYy1zaGEyNTYtMzJieXRlcw==";

    private PaymentPreviewTokenService newService() {
        return new PaymentPreviewTokenService(SECRET, 300);
    }

    private PaymentPreviewTokenService.PreviewSnapshot sampleSnapshot(Long userId) {
        return new PaymentPreviewTokenService.PreviewSnapshot(
                userId,
                501L,
                Currency.ARS,
                new BigDecimal("1500.00"),
                LocalDate.of(2026, 5, 6),
                new BigDecimal("1180.00"),
                LocalDate.of(2026, 5, 6),
                LocalDate.of(2026, 5, 5),
                "argentinadatos.com",
                ""
        );
    }

    @Test
    void issueAndParseRoundTrip() {
        PaymentPreviewTokenService service = newService();
        String token = service.issueToken(sampleSnapshot(42L));
        assertNotNull(token);

        Optional<PaymentPreviewTokenService.PreviewSnapshot> parsed = service.parseAndValidate(token, 42L);
        assertTrue(parsed.isPresent());
        PaymentPreviewTokenService.PreviewSnapshot s = parsed.get();
        assertEquals(42L, s.userId());
        assertEquals(501L, s.anchorInstallmentId());
        assertEquals(Currency.ARS, s.paymentCurrency());
        assertEquals(0, s.reportedAmount().compareTo(new BigDecimal("1500.00")));
        assertEquals(LocalDate.of(2026, 5, 6), s.reportedPaymentDate());
        assertEquals(0, s.quoteSellRate().compareTo(new BigDecimal("1180.00")));
        assertEquals(LocalDate.of(2026, 5, 5), s.quoteEffectiveDate());
        assertEquals("argentinadatos.com", s.quoteSource());
    }

    @Test
    void tokenForDifferentUserIsRejected() {
        PaymentPreviewTokenService service = newService();
        String token = service.issueToken(sampleSnapshot(42L));
        Optional<PaymentPreviewTokenService.PreviewSnapshot> parsed = service.parseAndValidate(token, 99L);
        assertTrue(parsed.isEmpty());
    }

    @Test
    void tamperedTokenIsRejected() {
        PaymentPreviewTokenService service = newService();
        String token = service.issueToken(sampleSnapshot(42L));
        String tampered = token.substring(0, token.length() - 2) + "AA";
        Optional<PaymentPreviewTokenService.PreviewSnapshot> parsed = service.parseAndValidate(tampered, 42L);
        assertTrue(parsed.isEmpty());
    }

    @Test
    void blankTokenIsRejected() {
        PaymentPreviewTokenService service = newService();
        assertTrue(service.parseAndValidate(null, 42L).isEmpty());
        assertTrue(service.parseAndValidate("   ", 42L).isEmpty());
    }
}
