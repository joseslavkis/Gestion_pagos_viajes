package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserLoginDTO;
import com.agencia.pagos.dtos.request.UserUpdateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.dtos.response.UserProfileDTO;
import com.agencia.pagos.repositories.RefreshTokenRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserRestControllerTest {

    private static final AtomicLong DNI_SEQUENCE = new AtomicLong(10_000_000L);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── GET /profile/{id} ───────────────────────────────────────────────────

    @Test
    void verPerfil_propioPerfil_devuelve200() throws Exception {
        UserCreateDTO dto = buildValidUser("ver-perfil-propio");
        TokenDTO tokens = signUp(dto);
        Long myId = extractId(tokens.accessToken());

        mockMvc.perform(get("/api/v1/users/profile/{id}", myId)
                .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(dto.email()))
                .andExpect(jsonPath("$.id").value(myId));
    }

    @Test
    void verPerfil_perfilAjeno_devuelve403() throws Exception {
        UserCreateDTO dto1 = buildValidUser("idor-user1");
        UserCreateDTO dto2 = buildValidUser("idor-user2");

        TokenDTO tokens1 = signUp(dto1);
        TokenDTO tokens2 = signUp(dto2);
        Long idUser2 = extractId(tokens2.accessToken());

        // user1 intenta ver el perfil de user2 → 403
        mockMvc.perform(get("/api/v1/users/profile/{id}", idUser2)
                .header("Authorization", "Bearer " + tokens1.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void verPerfil_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/users/profile/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verPerfil_adminPuedeVerCualquierPerfil_devuelve200() throws Exception {
        // Crear un user normal
        UserCreateDTO userDto = buildValidUser("admin-ve-user");
        TokenDTO userTokens = signUp(userDto);
        Long userId = extractId(userTokens.accessToken());

        // Crear un admin
        UserCreateDTO adminDto = buildValidUser("admin-ver");
        TokenDTO adminTokens = signUpAdmin(adminDto);

        // Admin ve el perfil del user → 200
        mockMvc.perform(get("/api/v1/users/profile/{id}", userId)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId));
    }

    // ── PATCH /update/me ────────────────────────────────────────────────────

    @Test
    void updateMe_conDatosValidos_devuelve200() throws Exception {
        TokenDTO tokens = signUp(buildValidUser("update-me"));

        UserUpdateDTO updateDto = new UserUpdateDTO("NuevoNombre", "NuevoApellido", null);

        mockMvc.perform(patch("/api/v1/users/update/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void updateMe_conPasswordCorta_devuelve400() throws Exception {
        TokenDTO tokens = signUp(buildValidUser("update-me-pass-corta"));

        UserUpdateDTO updateDto = new UserUpdateDTO(null, null, "corta");

        mockMvc.perform(patch("/api/v1/users/update/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMe_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(patch("/api/v1/users/update/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserUpdateDTO("Nombre", null, null))))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /delete/me ───────────────────────────────────────────────────

    @Test
    void deleteMe_devuelve200YDesactivaUsuario() throws Exception {
        TokenDTO tokens = signUp(buildValidUser("delete-me"));

        mockMvc.perform(delete("/api/v1/users/delete/me")
                .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void deleteMe_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/delete/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /admin/create ──────────────────────────────────────────────────

    @Test
    void adminCreate_siendoAdmin_devuelve201() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-creator"));
        UserCreateDTO nuevoAdmin = buildValidUser("nuevo-admin");

        mockMvc.perform(post("/api/v1/users/admin/create")
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(nuevoAdmin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void adminCreate_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-intenta-crear-admin"));
        UserCreateDTO nuevoAdmin = buildValidUser("admin-bloqueado");

        mockMvc.perform(post("/api/v1/users/admin/create")
                .header("Authorization", "Bearer " + userTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(nuevoAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreate_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/users/admin/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildValidUser("sin-auth"))))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /admin/update/{id} ────────────────────────────────────────────

    @Test
    void adminUpdate_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-updater"));
        Long adminId = extractId(adminTokens.accessToken());

        UserUpdateDTO updateDto = new UserUpdateDTO("AdminActualizado", null, null);

        mockMvc.perform(patch("/api/v1/users/admin/update/{id}", adminId)
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void adminUpdate_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-intenta-update-admin"));
        Long userId = extractId(userTokens.accessToken());

        mockMvc.perform(patch("/api/v1/users/admin/update/{id}", userId)
                .header("Authorization", "Bearer " + userTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserUpdateDTO("Nombre", null, null))))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /admin/delete/{id} ───────────────────────────────────────────

    @Test
    void adminDelete_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-deleter"));
        UserCreateDTO targetDto = buildValidUser("user-a-eliminar");
        TokenDTO targetTokens = signUp(targetDto);
        Long targetId = extractId(targetTokens.accessToken());

        mockMvc.perform(delete("/api/v1/users/admin/delete/{id}", targetId)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void adminDelete_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-intenta-delete"));
        Long userId = extractId(userTokens.accessToken());

        mockMvc.perform(delete("/api/v1/users/admin/delete/{id}", userId)
                .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDelete_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/admin/delete/1"))
                .andExpect(status().isUnauthorized());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    TokenDTO signUp(UserCreateDTO dto) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenDTO.class);
    }

    TokenDTO signUpAdmin(UserCreateDTO dto) throws Exception {
        // Primero creamos un admin existente para poder crear el nuevo via endpoint
        // Si no hay ninguno, usamos el seeder — pero en tests la DB está limpia,
        // así que necesitamos insertar el primer admin directamente via signup de admin
        // La única forma sin admin previo es crear via /admin/create con otro admin.
        // Para el primer admin usamos el UserService directamente con @Autowired o
        // lo creamos via un admin ya existente. Solución: hacemos signup normal y
        // manipulamos el rol via repository.
        TokenDTO tokens = signUp(dto);
        userRepository.findAll().stream()
                .filter(u -> u.getEmail().equals(dto.email()))
                .findFirst()
                .ifPresent(u -> {
                    u.setRole(com.agencia.pagos.entities.Role.ADMIN);
                    userRepository.save(u);
                });
        // Re-login para obtener token con rol ADMIN
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserLoginDTO(dto.email(), dto.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(loginResult.getResponse().getContentAsString(), TokenDTO.class);
    }

    Long extractId(String accessToken) throws Exception {
        // Decodificamos el JWT para extraer el subject (email) y luego buscamos el user
        String[] parts = accessToken.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String email = objectMapper.readTree(payload).get("sub").asText();
        return userRepository.findAll().stream()
                .filter(u -> u.getEmail().equals(email))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    UserCreateDTO buildValidUser(String prefix) {
        return new UserCreateDTO(
                uniqueEmail(prefix), "Password123!",
                "Test", "User", uniqueDni(),
                "123456789", "Alumno", "Colegio", "3ro"
        );
    }

    String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@agencia.com";
    }

    String uniqueDni() {
        return String.valueOf(DNI_SEQUENCE.getAndIncrement());
    }
}