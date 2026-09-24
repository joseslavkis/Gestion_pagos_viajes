package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.PaymentCalculationIntent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class PaymentPreviewTokenService {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_SUB = "sub";
    private static final String CLAIM_USER = "userId";
    private static final String CLAIM_ANCHOR = "anchorInstallmentId";
    private static final String CLAIM_CURRENCY = "paymentCurrency";
    private static final String CLAIM_AMOUNT = "reportedAmount";
    private static final String CLAIM_DATE = "reportedPaymentDate";
    private static final String CLAIM_QUOTE_RATE = "quoteSellRate";
    private static final String CLAIM_QUOTE_REQUESTED = "quoteRequestedDate";
    private static final String CLAIM_QUOTE_EFFECTIVE = "quoteEffectiveDate";
    private static final String CLAIM_QUOTE_SOURCE = "quoteSource";
    private static final String CLAIM_QUOTE_PROVIDER = "quoteProvider";
    private static final String CLAIM_QUOTE_TIMESTAMP = "quoteProviderTimestamp";
    private static final String CLAIM_CALCULATION_VERSION = "cv";
    private static final String CLAIM_INTENT = "intent";
    private static final String PREVIEW_TYPE = "payment-preview";
    public static final String CURRENT_CALCULATION_VERSION = "2";

    private final SecretKey signingKey;
    private final Duration tokenTtl;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PaymentPreviewTokenService(
            @Value("${jwt.access.secret}") String secret,
            @Value("${payment.preview.token-ttl-seconds:300}") long ttlSeconds
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.tokenTtl = Duration.ofSeconds(Math.max(30, ttlSeconds));
        this.clock = Clock.systemUTC();
        this.objectMapper = new ObjectMapper();
    }

    public String issueToken(PreviewSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateSnapshot(snapshot);
        Instant now = clock.instant();
        Instant expiry = now.plus(tokenTtl);
        return Jwts.builder()
                .claim(CLAIM_TYPE, PREVIEW_TYPE)
                .subject(snapshot.userId().toString())
                .claim(CLAIM_USER, snapshot.userId())
                .claim(CLAIM_ANCHOR, snapshot.anchorInstallmentId())
                .claim(CLAIM_CURRENCY, snapshot.paymentCurrency().name())
                .claim(CLAIM_AMOUNT, snapshot.reportedAmount().toPlainString())
                .claim(CLAIM_DATE, snapshot.reportedPaymentDate().toString())
                .claim(CLAIM_QUOTE_RATE, snapshot.quoteSellRate() == null
                        ? null
                        : snapshot.quoteSellRate().toPlainString())
                .claim(CLAIM_QUOTE_REQUESTED, snapshot.quoteRequestedDate() == null
                        ? null
                        : snapshot.quoteRequestedDate().toString())
                .claim(CLAIM_QUOTE_EFFECTIVE, snapshot.quoteEffectiveDate() == null
                        ? null
                        : snapshot.quoteEffectiveDate().toString())
                .claim(CLAIM_QUOTE_SOURCE, snapshot.quoteSource())
                .claim(CLAIM_QUOTE_PROVIDER, snapshot.quoteProvider())
                .claim(CLAIM_QUOTE_TIMESTAMP, snapshot.quoteProviderTimestamp())
                .claim(CLAIM_INTENT, snapshot.intent().name())
                .claim(CLAIM_CALCULATION_VERSION, snapshot.calculationVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<PreviewSnapshot> parseAndValidate(String token, Long userId) {
        TokenValidation validation = validateToken(token, userId);
        return validation.status() == TokenValidationStatus.VALID
                ? validation.snapshot()
                : Optional.empty();
    }

    public TokenValidation validateToken(String token, Long userId) {
        if (token == null || token.isBlank()) {
            return TokenValidation.invalid();
        }
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            Claims claims = parsed.getPayload();
            if (!hasExpectedIdentity(claims, userId)) {
                return TokenValidation.invalid();
            }
            return TokenValidation.valid(toSnapshot(claims, userId));
        } catch (ExpiredJwtException expired) {
            return hasExpectedIdentity(expired.getClaims(), userId)
                    ? TokenValidation.expired()
                    : TokenValidation.invalid();
        } catch (RuntimeException e) {
            return TokenValidation.invalid();
        }
    }

    private boolean hasExpectedIdentity(Claims claims, Long userId) {
        if (claims == null
                || !PREVIEW_TYPE.equals(claims.get(CLAIM_TYPE, String.class))
                || !CURRENT_CALCULATION_VERSION.equals(claims.get(CLAIM_CALCULATION_VERSION, String.class))) {
            return false;
        }
        Object userIdClaim = claims.get(CLAIM_USER);
        String subject = claims.getSubject();
        return userIdClaim != null
                && Long.valueOf(userIdClaim.toString()).equals(userId)
                && userId != null
                && userId.toString().equals(subject);
    }

    private PreviewSnapshot toSnapshot(Claims claims, Long userId) {
        Long anchor = ((Number) claims.get(CLAIM_ANCHOR)).longValue();
        String currency = claims.get(CLAIM_CURRENCY, String.class);
        BigDecimal amount = new BigDecimal(claims.get(CLAIM_AMOUNT, String.class));
        java.time.LocalDate date = java.time.LocalDate.parse(claims.get(CLAIM_DATE, String.class));
        String quoteSource = claims.get(CLAIM_QUOTE_SOURCE, String.class);
        String quoteProvider = claims.get(CLAIM_QUOTE_PROVIDER, String.class);
        String quoteTimestamp = claims.get(CLAIM_QUOTE_TIMESTAMP, String.class);
        PaymentCalculationIntent intent = PaymentCalculationIntent.valueOf(
                claims.get(CLAIM_INTENT, String.class));
        BigDecimal quoteRate = optionalDecimal(claims.get(CLAIM_QUOTE_RATE));
        java.time.LocalDate quoteRequested = optionalDate(claims.get(CLAIM_QUOTE_REQUESTED));
        java.time.LocalDate quoteEffective = optionalDate(claims.get(CLAIM_QUOTE_EFFECTIVE));
        return new PreviewSnapshot(
                userId,
                anchor,
                com.agencia.pagos.shared.money.Currency.valueOf(currency),
                amount,
                date,
                quoteRate,
                quoteRequested,
                quoteEffective,
                quoteSource,
                quoteProvider,
                quoteTimestamp,
                intent,
                CURRENT_CALCULATION_VERSION
        );
    }

    private BigDecimal optionalDecimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private java.time.LocalDate optionalDate(Object value) {
        return value == null ? null : java.time.LocalDate.parse(value.toString());
    }

    public String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize preview snapshot", e);
        }
    }

    public Map<String, Object> describeToken(PreviewSnapshot snapshot) {
        return Map.of(
                "userId", snapshot.userId(),
                "anchorInstallmentId", snapshot.anchorInstallmentId(),
                "paymentCurrency", snapshot.paymentCurrency().name(),
                "reportedAmount", snapshot.reportedAmount().toPlainString(),
                "reportedPaymentDate", snapshot.reportedPaymentDate().toString()
        );
    }

    public record PreviewSnapshot(
            Long userId,
            Long anchorInstallmentId,
            com.agencia.pagos.shared.money.Currency paymentCurrency,
            BigDecimal reportedAmount,
            java.time.LocalDate reportedPaymentDate,
            BigDecimal quoteSellRate,
            java.time.LocalDate quoteRequestedDate,
            java.time.LocalDate quoteEffectiveDate,
            String quoteSource,
            String quoteProvider,
            String quoteProviderTimestamp,
            PaymentCalculationIntent intent,
            String calculationVersion
    ) {
        public PreviewSnapshot(
                Long userId,
                Long anchorInstallmentId,
                com.agencia.pagos.shared.money.Currency paymentCurrency,
                BigDecimal reportedAmount,
                java.time.LocalDate reportedPaymentDate,
                BigDecimal quoteSellRate,
                java.time.LocalDate quoteRequestedDate,
                java.time.LocalDate quoteEffectiveDate,
                String quoteSource,
                String quoteProviderTimestamp
        ) {
            this(
                    userId,
                    anchorInstallmentId,
                    paymentCurrency,
                    reportedAmount,
                    reportedPaymentDate,
                    quoteSellRate,
                    quoteRequestedDate,
                    quoteEffectiveDate,
                    quoteSource,
                    quoteSource,
                    quoteProviderTimestamp,
                    PaymentCalculationIntent.MANUAL,
                    CURRENT_CALCULATION_VERSION
            );
        }
    }

    public enum TokenValidationStatus {
        VALID,
        EXPIRED,
        INVALID
    }

    public record TokenValidation(TokenValidationStatus status, Optional<PreviewSnapshot> snapshot) {

        private static TokenValidation valid(PreviewSnapshot snapshot) {
            return new TokenValidation(TokenValidationStatus.VALID, Optional.of(snapshot));
        }

        private static TokenValidation expired() {
            return new TokenValidation(TokenValidationStatus.EXPIRED, Optional.empty());
        }

        private static TokenValidation invalid() {
            return new TokenValidation(TokenValidationStatus.INVALID, Optional.empty());
        }
    }

    private static void validateSnapshot(PreviewSnapshot snapshot) {
        if (!CURRENT_CALCULATION_VERSION.equals(snapshot.calculationVersion())) {
            throw new IllegalArgumentException("Unsupported payment calculation version");
        }
        if (snapshot.intent() == null) {
            throw new IllegalArgumentException("Payment calculation intent is required");
        }
        if (snapshot.quoteSellRate() == null) {
            return;
        }
        if (snapshot.quoteRequestedDate() == null
                || snapshot.quoteEffectiveDate() == null
                || snapshot.quoteSource() == null
                || snapshot.quoteSource().isBlank()
                || snapshot.quoteProvider() == null
                || snapshot.quoteProvider().isBlank()) {
            throw new IllegalArgumentException("Exchange-rate quote identity is incomplete");
        }
    }
}
