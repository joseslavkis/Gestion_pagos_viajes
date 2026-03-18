package com.agencia.pagos.services;

import com.agencia.pagos.config.security.JwtService;
import com.agencia.pagos.config.security.JwtUserDetails;
import com.agencia.pagos.dtos.request.RefreshDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserUpdateDTO;
import com.agencia.pagos.dtos.response.StatusResponseDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.dtos.response.UserProfileDTO;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.entities.user.UserCredentials;
import com.agencia.pagos.entities.refresh_token.RefreshToken;
import com.agencia.pagos.repositories.UserRepository;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class UserService implements UserDetailsService {

    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;

    @Autowired
    UserService(
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            UserRepository userRepository,
            RefreshTokenService refreshTokenService
    ) {
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository
                .findByEmail(email)
                .orElseThrow(() -> {
                    var msg = String.format("Email '%s' not found", email);
                    return new UsernameNotFoundException(msg);
                });
    }

    public Optional<TokenDTO> createUser(UserCreateDTO data) {
        if (userRepository.findByEmail(data.email()).isPresent()) {
            return Optional.empty();
        }

        var user = data.asUser(passwordEncoder::encode, Role.USER);
        userRepository.save(user);
        return Optional.of(generateTokens(user));
    }

    public Optional<TokenDTO> createUser(UserCreateDTO data, Role role) {
        if (userRepository.findByEmail(data.email()).isPresent()) {
            return Optional.empty();
        }

        var user = data.asUser(passwordEncoder::encode, role);
        userRepository.save(user);
        return Optional.of(generateTokens(user));
    }

    public Optional<TokenDTO> loginUser(UserCredentials data) {
        Optional<User> maybeUser = userRepository.findByEmail(data.email());
        return maybeUser
                .filter(user -> passwordEncoder.matches(data.password(), user.getPassword()))
                .map(this::generateTokens);
    }

    public Optional<TokenDTO> refresh(RefreshDTO data) {
        return refreshTokenService.findByValue(data.refreshToken())
                .map(RefreshToken::user)
                .map(this::generateTokens);
    }

    public Optional<UserProfileDTO> getUserProfileById(Long id) {
        return userRepository.findById(id)
                .map(user -> new UserProfileDTO(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getLastname(),
                user.getRole()
                ));
    }

    public Optional<User> deleteUser(Long id) {
        Optional<User> user = userRepository.findById(id);
        if (user.isPresent()) {
            refreshTokenService.deleteByUser(user.get());
            userRepository.delete(user.get());
        }
        return user;
    }

    private TokenDTO generateTokens(User user) {
        String accessToken = jwtService.createToken(new JwtUserDetails(
                user.getUsername(),
                user.getRole().name()
        ));
        RefreshToken refreshToken = refreshTokenService.createFor(user);
        return new TokenDTO(accessToken, refreshToken.value());
    }

    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new EntityNotFoundException("User not found"));
    }

    public ResponseEntity<StatusResponseDTO> updateAdmin(Long id, UserUpdateDTO userDTO) {
        User findedUser = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Admin not found"));

        if (Role.ADMIN != findedUser.getRole()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new StatusResponseDTO("error", "Target user is not admin"));
        }

        if (userDTO.name() != null) {
            findedUser.setName(userDTO.name());
        }
        if (userDTO.lastname() != null) {
            findedUser.setLastname(userDTO.lastname());
        }
        if (userDTO.password() != null) {
            findedUser.setPassword(passwordEncoder.encode(userDTO.password()));
        }

        userRepository.save(findedUser);
        return ResponseEntity.status(HttpStatus.OK).body(new StatusResponseDTO("success", "Admin updated"));
    }

    public ResponseEntity<StatusResponseDTO> updateUser(UserUpdateDTO userDTO, Long id) {
        User findedUser = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        if (userDTO.name() != null) {
            findedUser.setName(userDTO.name());
        }
        if (userDTO.lastname() != null) {
            findedUser.setLastname(userDTO.lastname());
        }
        if (userDTO.password() != null) {
            findedUser.setPassword(passwordEncoder.encode(userDTO.password()));
        }

        userRepository.save(findedUser);
        return ResponseEntity.status(HttpStatus.OK).body(new StatusResponseDTO("success", "User updated"));
    }
}