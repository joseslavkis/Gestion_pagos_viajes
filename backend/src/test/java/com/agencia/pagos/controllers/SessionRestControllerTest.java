package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.RefreshDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserLoginDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionRestControllerTest extends ControllerIntegrationTestSupport {

    @Test
    void signUp_conDatosValidos_devuelve201ConTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildValidUser("signup-ok"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void signUp_conEmailDuplicado_devuelve409() throws Exception {
        UserCreateDTO dto = buildValidUser("signup-duplicado");

        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void signUp_conPasswordCorta_devuelve400() throws Exception {
        UserCreateDTO dto = new UserCreateDTO(
                uniqueEmail("pass-corta"), "Abc1",
                "Test", "User", uniqueDni(),
                "123456789", "Alumno", uniqueDni(), "Colegio", "3ro"
        );

        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUp_conEmailInvalido_devuelve400() throws Exception {
        UserCreateDTO dto = new UserCreateDTO(
                "email-invalido", "Password123!",
                "Test", "User", uniqueDni(),
                "123456789", "Alumno", uniqueDni(), "Colegio", "3ro"
        );

        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_conCredencialesValidas_devuelve200ConTokens() throws Exception {
        UserCreateDTO dto = buildValidUser("login-ok");
        signUp(dto);

        mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserLoginDTO(dto.email(), dto.password()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void login_conPasswordIncorrecta_devuelve401() throws Exception {
        UserCreateDTO dto = buildValidUser("login-pass-wrong");
        signUp(dto);

        mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserLoginDTO(dto.email(), "PasswordIncorrecta123!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_conEmailInexistente_devuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserLoginDTO(uniqueEmail("inexistente"), "Password123!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_conTokenValido_devuelve200ConTokensNuevos() throws Exception {
        UserCreateDTO dto = buildValidUser("refresh-ok");
        TokenDTO tokens = signUp(dto);

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshDTO(tokens.refreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void refresh_conTokenInvalido_devuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshDTO("token-invalido"))))
                .andExpect(status().isUnauthorized());
    }

}