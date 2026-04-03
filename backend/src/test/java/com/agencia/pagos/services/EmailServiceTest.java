package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.ContactMessageDTO;
import com.agencia.pagos.entities.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EmailServiceTest {

    private MockRestServiceServer server;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        emailService = new EmailService(
                builder,
                "destino@proyectova.com",
                "proyectova2@gmail.com",
                "Proyecto VA",
                "proyectova2@gmail.com",
                "brevo-api-key-test",
                "http://localhost:30003"
        );
    }

    @Test
    void isDeliveryConfigured_dependeDeBrevo() {
        assertTrue(emailService.isDeliveryConfigured());

        EmailService unconfigured = new EmailService(
                RestClient.builder(),
                "destino@proyectova.com",
                "",
                "Proyecto VA",
                "",
                "",
                "http://localhost:30003"
        );

        assertFalse(unconfigured.isDeliveryConfigured());
    }

    @Test
    void sendContactMessage_enviaPayloadBrevoConReplyToDelUsuario() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "brevo-api-key-test"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(content().json("""
                        {
                          "sender": { "email": "proyectova2@gmail.com", "name": "Proyecto VA" },
                          "to": [{ "email": "destino@proyectova.com" }],
                          "replyTo": { "email": "jose@example.com", "name": "Jose" },
                          "subject": "Nueva consulta - Viajes Pagos"
                        }
                        """, false))
                .andExpect(content().string(containsString("Nueva consulta")))
                .andExpect(content().string(containsString("Jose")))
                .andExpect(content().string(containsString("jose@example.com")))
                .andExpect(content().string(containsString("Hola equipo")))
                .andRespond(withSuccess("{\"messageId\":\"abc123\"}", MediaType.APPLICATION_JSON));

        emailService.sendContactMessage(new ContactMessageDTO("Jose", "jose@example.com", "Hola equipo"));

        server.verify();
    }

    @Test
    void sendInstallmentReminder_enviaPayloadBrevoConContenidoHtml() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "brevo-api-key-test"))
                .andExpect(content().json("""
                        {
                          "sender": { "email": "proyectova2@gmail.com", "name": "Proyecto VA" },
                          "to": [{ "email": "familia@example.com", "name": "Jose" }],
                          "replyTo": { "email": "proyectova2@gmail.com", "name": "Proyecto VA" }
                        }
                        """, false))
                .andExpect(content().string(containsString("Viaje Bariloche")))
                .andExpect(content().string(containsString("Recordatorio")))
                .andRespond(withSuccess("{\"messageId\":\"abc123\"}", MediaType.APPLICATION_JSON));

        emailService.sendInstallmentReminder(
                "familia@example.com",
                "Jose",
                List.of(new EmailService.InstallmentReminderMailItem(
                        "Viaje Bariloche",
                        3,
                        LocalDate.of(2026, 4, 10),
                        BigDecimal.valueOf(150000),
                        Currency.ARS,
                        EmailService.ReminderKind.DUE_SOON
                ))
        );

        server.verify();
    }

    @Test
    void sendInstallmentReminder_enviaAsuntoYContenidoParaCuotasVencidasHaceSieteDias() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "brevo-api-key-test"))
                .andExpect(content().json("""
                        {
                          "sender": { "email": "proyectova2@gmail.com", "name": "Proyecto VA" },
                          "to": [{ "email": "familia@example.com", "name": "Jose" }],
                          "replyTo": { "email": "proyectova2@gmail.com", "name": "Proyecto VA" },
                          "subject": "Recordatorio de cuotas vencidas hace 7 dias: 1"
                        }
                        """, false))
                .andExpect(content().string(containsString("Viaje Mendoza")))
                .andExpect(content().string(containsString("Cuotas vencidas hace 7 dias")))
                .andExpect(content().string(containsString("Estado: vencida hace 7 dias")))
                .andRespond(withSuccess("{\"messageId\":\"abc123\"}", MediaType.APPLICATION_JSON));

        emailService.sendInstallmentReminder(
                "familia@example.com",
                "Jose",
                List.of(new EmailService.InstallmentReminderMailItem(
                        "Viaje Mendoza",
                        2,
                        LocalDate.of(2026, 4, 3),
                        BigDecimal.valueOf(90000),
                        Currency.ARS,
                        EmailService.ReminderKind.OVERDUE_7_DAYS
                ))
        );

        server.verify();
    }

    @Test
    void sendPasswordResetEmail_enviaPayloadBrevoConLinkDeReset() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "brevo-api-key-test"))
                .andExpect(content().json("""
                        {
                          "sender": { "email": "proyectova2@gmail.com", "name": "Proyecto VA" },
                          "to": [{ "email": "familia@example.com", "name": "Jose" }],
                          "replyTo": { "email": "proyectova2@gmail.com", "name": "Proyecto VA" },
                          "subject": "Recuperación de contraseña - ProyectoVA"
                        }
                        """, false))
                .andExpect(content().string(containsString("reset-password?token=token-123")))
                .andRespond(withSuccess("{\"messageId\":\"abc123\"}", MediaType.APPLICATION_JSON));

        emailService.sendPasswordResetEmail("familia@example.com", "Jose", "token-123");

        server.verify();
    }
}
