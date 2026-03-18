package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserLoginDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.repositories.RefreshTokenRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class ControllerIntegrationTestSupport {

    private static final AtomicLong DNI_SEQUENCE = new AtomicLong(10_000_000L);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    @Autowired
    protected UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    protected TokenDTO signUp(UserCreateDTO dto) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenDTO.class);
    }

    protected TokenDTO login(UserLoginDTO dto) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenDTO.class);
    }

    protected TokenDTO signUpAdmin(UserCreateDTO dto) throws Exception {
        signUp(dto);
        promoteUserToAdmin(dto.email());
        return login(new UserLoginDTO(dto.email(), dto.password()));
    }

    protected Long extractId(String accessToken) throws Exception {
        String[] parts = accessToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String email = objectMapper.readTree(payload).get("sub").asText();
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    protected UserCreateDTO buildValidUser(String prefix) {
        return new UserCreateDTO(
                uniqueEmail(prefix),
                "Password123!",
                "Test",
                "User",
                uniqueDni(),
                "123456789",
                "Alumno",
                "Colegio",
                "3ro"
        );
    }

    protected String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@agencia.com";
    }

    protected String uniqueDni() {
        return String.valueOf(DNI_SEQUENCE.getAndIncrement());
    }

    private void promoteUserToAdmin(String email) {
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(Role.ADMIN);
        userRepository.save(user);
    }
}