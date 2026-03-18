package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserUpdateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserRestControllerTest extends ControllerIntegrationTestSupport {

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

}