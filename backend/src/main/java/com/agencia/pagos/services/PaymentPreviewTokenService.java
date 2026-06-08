package com.agencia.pagos.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
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
    private static final String CLAIM_QUOTE_TIMESTAMP = "quoteProviderTimestamp";
    private static final String PREVIEW_TYPE = "payment-preview";

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
                .claim(CLAIM_QUOTE_TIMESTAMP, snapshot.quoteProviderTimestamp())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<PreviewSnapshot> parseAndValidate(String token, Long userId) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            Claims claims = parsed.getPayload();
            if (!PREVIEW_TYPE.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            Object userIdClaim = claims.get(CLAIM_USER);
            if (userIdClaim == null || !Long.valueOf(userIdClaim.toString()).equals(userId)) {
                return Optional.empty();
            }
            Long anchor = ((Number) claims.get(CLAIM_ANCHOR)).longValue();
            String currency = claims.get(CLAIM_CURRENCY, String.class);
            BigDecimal amount = new BigDecimal(claims.get(CLAIM_AMOUNT, String.class));
            java.time.LocalDate date = java.time.LocalDate.parse(claims.get(CLAIM_DATE, String.class));
            String quoteSource = claims.get(CLAIM_QUOTE_SOURCE, String.class);
            String quoteTimestamp = claims.get(CLAIM_QUOTE_TIMESTAMP, String.class);
            BigDecimal quoteRate = null;
            Object rate = claims.get(CLAIM_QUOTE_RATE);
            if (rate != null) {
                quoteRate = new BigDecimal(rate.toString());
            }
            java.time.LocalDate quoteRequested = null;
            Object requested = claims.get(CLAIM_QUOTE_REQUESTED);
            if (requested != null) {
                quoteRequested = java.time.LocalDate.parse(requested.toString());
            }
            java.time.LocalDate quoteEffective = null;
            Object effective = claims.get(CLAIM_QUOTE_EFFECTIVE);
            if (effective != null) {
                quoteEffective = java.time.LocalDate.parse(effective.toString());
            }
            return Optional.of(new PreviewSnapshot(
                    userId,
                    anchor,
                    com.agencia.pagos.entities.Currency.valueOf(currency),
                    amount,
                    date,
                    quoteRate,
                    quoteRequested,
                    quoteEffective,
                    quoteSource,
                    quoteTimestamp
            ));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
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
            com.agencia.pagos.entities.Currency paymentCurrency,
            BigDecimal reportedAmount,
            java.time.LocalDate reportedPaymentDate,
            BigDecimal quoteSellRate,
            java.time.LocalDate quoteRequestedDate,
            java.time.LocalDate quoteEffectiveDate,
            String quoteSource,
            String quoteProviderTimestamp
    ) {
    }
}
