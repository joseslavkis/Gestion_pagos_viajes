package com.agencia.pagos.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    /** Clave Pre-computada en el constructor — evita decodificar Base64 en cada request. */
    private final SecretKey signingKey;
    private final Long expiration;

    @Autowired
    public JwtService(
            @Value("${jwt.access.secret}") String secret,
            @Value("${jwt.access.expiration}") Long expiration
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expiration = expiration;
    }

    public String createToken(JwtUserDetails claims) {
        String role = claims.role().startsWith("ROLE_")
                ? claims.role()
                : "ROLE_" + claims.role();

        return Jwts.builder()
                .subject(claims.username())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .claim("role", role)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<JwtUserDetails> extractVerifiedUserDetails(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getSubject() != null && claims.get("role") instanceof String role) {
                return Optional.of(new JwtUserDetails(claims.getSubject(), role));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}

