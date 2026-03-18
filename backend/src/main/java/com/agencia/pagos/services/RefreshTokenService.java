package com.agencia.pagos.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.entities.refresh_token.RefreshToken;
import com.agencia.pagos.repositories.RefreshTokenRepository;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@Transactional
public class RefreshTokenService {

    private final Long expiration;
    private final Integer byteSize;
    private final RefreshTokenRepository refreshTokenRepository;

    @Autowired
    RefreshTokenService(
            @Value("${jwt.refresh.expiration}") Long expiration,
            @Value("${jwt.refresh.bytes}") Integer byteSize,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.expiration = expiration;
        this.byteSize = byteSize;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public RefreshToken createFor(User user) {
        refreshTokenRepository.deleteByUser(user);
        String value = getRandomString();
        RefreshToken result = new RefreshToken(value, user, getExpirationFor(Instant.now()));
        refreshTokenRepository.save(result);
        return result;
    }

    public Optional<RefreshToken> findByValue(@NonNull String value) {
        return refreshTokenRepository.findById(value)
                .filter(RefreshToken::isValid)
                .map(token -> {
                    refreshTokenRepository.delete(token);
                    return token;
                });
    }

    public void deleteByUser(User user) {
        refreshTokenRepository.deleteByUser(user);
    }

    String getRandomString() {
        SecureRandom random = new SecureRandom();
        byte[] randomBytes = new byte[this.byteSize];
        random.nextBytes(randomBytes);
        return new BigInteger(1, randomBytes).toString(32);
    }

    Instant getExpirationFor(Instant reference) {
        return reference.plus(expiration, ChronoUnit.MILLIS);
    }
}