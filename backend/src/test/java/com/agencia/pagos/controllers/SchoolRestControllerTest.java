package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.SchoolCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.repositories.SchoolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SchoolRestControllerTest extends ControllerIntegrationTestSupport {

    @Autowired
    private SchoolRepository schoolRepository;

    @AfterEach
    void cleanUpSchools() {
        schoolRepository.deleteAll();
    }

    @Test
    void create_siendoAdmin_devuelve201YLista() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-school-create"));
        SchoolCreateDTO dto = new SchoolCreateDTO("Colegio San Jose");

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Colegio San Jose"));

        mockMvc.perform(get("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Colegio San Jose"));
    }

    @Test
    void create_conDuplicadoNormalizado_devuelve409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-school-duplicate"));

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchoolCreateDTO("Colegio San Jose"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchoolCreateDTO("  colegio   san   jose  "))))
                .andExpect(status().isConflict());
    }

    @Test
    void userNoAdmin_noPuedeCrearPeroPuedeListar() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-school-list"));
        TokenDTO userTokens = signUp(buildValidUser("user-school-list"));

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchoolCreateDTO("Colegio Ward"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + userTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchoolCreateDTO("Otro Colegio"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/schools")
                        .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Colegio Ward"));
    }

    @Test
    void getSchools_sinAutenticacion_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-school-public"));

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchoolCreateDTO("Colegio Publico"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/schools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Colegio Publico"));
    }
}
