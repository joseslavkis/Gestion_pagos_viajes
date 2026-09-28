package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.PaymentCalculationIntent;
import com.agencia.pagos.shared.money.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Arrays;
import java.time.Instant;
import java.util.Date;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

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
                "official",
                "argentinadatos.com",
                "2026-05-05T12:30:00Z",
                PaymentCalculationIntent.REMAINING,
                PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION
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
        assertEquals("official", s.quoteSource());
        assertEquals("argentinadatos.com", s.quoteProvider());
        assertEquals("2026-05-05T12:30:00Z", s.quoteProviderTimestamp());
        assertEquals(PaymentCalculationIntent.REMAINING, s.intent());
        assertEquals("2", s.calculationVersion());
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

    @Test
    void snapshotContractCarriesProviderAndCalculationVersion() {
        var componentNames = Arrays.stream(PaymentPreviewTokenService.PreviewSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertTrue(componentNames.contains("quoteProvider"));
        assertTrue(componentNames.contains("calculationVersion"));
    }

    @Test
    void issuedTokenDeclaresCalculationVersionTwoAndIntent() throws Exception {
        PaymentPreviewTokenService service = newService();
        String token = service.issueToken(sampleSnapshot(42L));

        String payload = new String(
                java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8
        );

        assertTrue(payload.contains("\"cv\":\"2\""));
        assertTrue(payload.contains("\"intent\":\"REMAINING\""));
        assertTrue(payload.contains("\"quoteProvider\""));
    }

    @Test
    void baseBackendTokenWithoutCalculationVersionRequiresImmediateRecalculation() {
        PaymentPreviewTokenService service = newService();

        PaymentPreviewTokenService.TokenValidation validation = service.validateToken(legacyToken(null), 42L);

        assertEquals(PaymentPreviewTokenService.TokenValidationStatus.INVALID, validation.status());
        assertTrue(validation.snapshot().isEmpty());
    }

    @Test
    void versionlessAndNoncurrentTokensAreRejectedWhileVersionTwoRemainsValid() {
        PaymentPreviewTokenService service = newService();

        assertTrue(service.parseAndValidate(legacyToken(null), 42L).isEmpty());
        assertTrue(service.parseAndValidate(legacyToken("1"), 42L).isEmpty());
        assertTrue(service.parseAndValidate(legacyToken("3"), 42L).isEmpty());

        PaymentPreviewTokenService.PreviewSnapshot versionTwo =
                service.parseAndValidate(legacyToken("2"), 42L).orElseThrow();
        assertEquals("2", versionTwo.calculationVersion());
        assertEquals(PaymentCalculationIntent.REMAINING, versionTwo.intent());
    }

    @Test
    void expiredVersionTwoTokenRemainsExpired() {
        PaymentPreviewTokenService service = newService();
        String expired = tokenWithVersionAndWindow("2", Instant.now().minusSeconds(600), Instant.now().minusSeconds(300));

        PaymentPreviewTokenService.TokenValidation validation = service.validateToken(expired, 42L);

        assertEquals(PaymentPreviewTokenService.TokenValidationStatus.EXPIRED, validation.status());
        assertTrue(validation.snapshot().isEmpty());
        assertTrue(service.parseAndValidate(expired, 42L).isEmpty());
    }

    @Test
    void expiredTokenForAnotherIdentityRemainsInvalid() {
        PaymentPreviewTokenService service = newService();
        String expired = tokenWithVersionAndWindow("2", Instant.now().minusSeconds(600), Instant.now().minusSeconds(300));

        PaymentPreviewTokenService.TokenValidation validation = service.validateToken(expired, 99L);

        assertEquals(PaymentPreviewTokenService.TokenValidationStatus.INVALID, validation.status());
        assertTrue(validation.snapshot().isEmpty());
    }

    private String legacyToken(String calculationVersion) {
        Instant now = Instant.now();
        return tokenWithVersionAndWindow(calculationVersion, now, now.plusSeconds(300));
    }

    private String tokenWithVersionAndWindow(String calculationVersion, Instant issuedAt, Instant expiresAt) {
        var builder = Jwts.builder()
                .claim("type", "payment-preview")
                .subject("42")
                .claim("userId", 42L)
                .claim("anchorInstallmentId", 501L)
                .claim("paymentCurrency", "ARS")
                .claim("reportedAmount", "1500.00")
                .claim("reportedPaymentDate", "2026-05-06")
                .claim("quoteSellRate", "1180.00")
                .claim("quoteRequestedDate", "2026-05-06")
                .claim("quoteEffectiveDate", "2026-05-05")
                .claim("quoteSource", "official")
                .claim("quoteProvider", "provider-a")
                .claim("intent", "REMAINING")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt));
        if (calculationVersion != null) {
            builder.claim("cv", calculationVersion);
        }
        return builder
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), Jwts.SIG.HS256)
                .compact();
    }
}
