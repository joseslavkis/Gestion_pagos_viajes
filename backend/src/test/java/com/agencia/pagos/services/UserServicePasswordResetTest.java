package com.agencia.pagos.services;

import com.agencia.pagos.config.security.JwtService;
import com.agencia.pagos.dtos.request.ResetPasswordDTO;
import com.agencia.pagos.entities.PasswordResetToken;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PasswordResetTokenRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServicePasswordResetTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserService userService;

    @Test
    void requestPasswordReset_conUsuarioExistente_guardaTokenYEnviaEmail() {
        User user = buildUser("jose@example.com", "Jose");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        userService.requestPasswordReset(user.getEmail());

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).deleteByUser(user);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        verify(emailService).sendPasswordResetEmail(
                user.getEmail(),
                user.getName(),
                tokenCaptor.getValue().getToken()
        );

        PasswordResetToken savedToken = tokenCaptor.getValue();
        assertEquals(user, savedToken.getUser());
        assertFalse(savedToken.isUsed());
        assertTrue(savedToken.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(59)));
    }

    @Test
    void requestPasswordReset_conUsuarioInexistente_noHaceNada() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        userService.requestPasswordReset("missing@example.com");

        verify(passwordResetTokenRepository, never()).deleteByUser(any());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any(), any());
    }

    @Test
    void resetPassword_conTokenValido_actualizaPasswordMarcaUsoYRevocaRefreshTokens() {
        User user = buildUser("jose@example.com", "Jose");
        PasswordResetToken token = PasswordResetToken.builder()
                .token("token-ok")
                .user(user)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false)
                .build();

        when(passwordResetTokenRepository.findByToken("token-ok")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NuevaPassword123!")).thenReturn("encoded-password");

        userService.resetPassword(new ResetPasswordDTO("token-ok", "NuevaPassword123!"));

        assertEquals("encoded-password", user.getPassword());
        assertTrue(token.isUsed());
        verify(userRepository).save(user);
        verify(passwordResetTokenRepository).save(token);
        verify(refreshTokenService).deleteByUser(user);
    }

    @Test
    void resetPassword_conTokenInvalido_lanzaError() {
        when(passwordResetTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> userService.resetPassword(new ResetPasswordDTO("missing", "NuevaPassword123!"))
        );

        assertEquals("El enlace de recuperación no es válido.", error.getMessage());
    }

    @Test
    void resetPassword_conTokenUsado_lanzaError() {
        User user = buildUser("jose@example.com", "Jose");
        PasswordResetToken token = PasswordResetToken.builder()
                .token("used-token")
                .user(user)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(true)
                .build();
        when(passwordResetTokenRepository.findByToken("used-token")).thenReturn(Optional.of(token));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> userService.resetPassword(new ResetPasswordDTO("used-token", "NuevaPassword123!"))
        );

        assertEquals("Este enlace ya fue utilizado.", error.getMessage());
    }

    @Test
    void resetPassword_conTokenExpirado_lanzaError() {
        User user = buildUser("jose@example.com", "Jose");
        PasswordResetToken token = PasswordResetToken.builder()
                .token("expired-token")
                .user(user)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .used(false)
                .build();
        when(passwordResetTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(token));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> userService.resetPassword(new ResetPasswordDTO("expired-token", "NuevaPassword123!"))
        );

        assertEquals("El enlace de recuperación expiró. Solicitá uno nuevo.", error.getMessage());
    }

    private User buildUser(String email, String name) {
        User user = new User();
        user.setRole(Role.USER);
        user.setName(name);
        user.setLastname("Test");
        user.setPassword("secret");
        user.setActive(true);
        setField(user, "email", email);
        return user;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set field " + fieldName, e);
        }
    }
}
