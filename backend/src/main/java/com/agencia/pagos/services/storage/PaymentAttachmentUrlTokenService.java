package com.agencia.pagos.services.storage;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;

@Service
public class PaymentAttachmentUrlTokenService {

    private static final String SUBJECT = "payment-attachment";
    private static final String REFERENCE_CLAIM = "ref";

    private final SecretKey signingKey;

    public PaymentAttachmentUrlTokenService(@Value("${jwt.access.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    public String createToken(String storedValue, long expirationMinutes) {
        long effectiveMinutes = expirationMinutes > 0 ? expirationMinutes : 15L;
        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + effectiveMinutes * 60_000L);

        return Jwts.builder()
                .subject(SUBJECT)
                .issuedAt(issuedAt)
                .expiration(expiration)
                .claim(REFERENCE_CLAIM, storedValue)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<String> extractStoredValue(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (SUBJECT.equals(claims.getSubject()) && claims.get(REFERENCE_CLAIM) instanceof String storedValue) {
                return Optional.of(storedValue);
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}
