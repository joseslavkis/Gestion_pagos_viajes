package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PasswordResetToken;
import com.agencia.pagos.entities.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    void deleteByUser(User user);
}
