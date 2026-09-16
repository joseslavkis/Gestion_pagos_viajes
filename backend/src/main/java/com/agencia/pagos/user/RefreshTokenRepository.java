package com.agencia.pagos.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.agencia.pagos.user.User;
import com.agencia.pagos.user.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    @Transactional
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user = :user")
    void deleteByUser(User user);
}
