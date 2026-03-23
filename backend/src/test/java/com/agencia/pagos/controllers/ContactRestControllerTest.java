package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.ContactMessageDTO;
import com.agencia.pagos.services.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ContactRestControllerTest extends ControllerIntegrationTestSupport {

    @MockBean
    private EmailService emailService;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Test
    void send_conPayloadValido_devuelve202() throws Exception {
        ContactMessageDTO dto = new ContactMessageDTO(
                "Jose",
                "jose@example.com",
                "Hola, quiero mas informacion"
        );

        mockMvc.perform(post("/api/v1/contact/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Message queued"));

        ArgumentCaptor<ContactMessageDTO> captor = ArgumentCaptor.forClass(ContactMessageDTO.class);
        verify(emailService).sendContactMessage(captor.capture());
        assertEquals(dto, captor.getValue());
    }

    @Test
    void send_conEmailInvalido_devuelve400() throws Exception {
        ContactMessageDTO dto = new ContactMessageDTO(
                "Jose",
                "email-invalido",
                "Hola"
        );

        mockMvc.perform(post("/api/v1/contact/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }
}
