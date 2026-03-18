package com.agencia.pagos.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.entities.refresh_token.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    @Transactional
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user = :user")
    void deleteByUser(User user);
}