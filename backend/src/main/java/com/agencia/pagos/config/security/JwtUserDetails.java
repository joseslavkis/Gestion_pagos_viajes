package com.agencia.pagos.config.security;

public record JwtUserDetails (
        String username,
        String role
) {}