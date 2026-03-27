package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.ContactMessageDTO;
import com.agencia.pagos.entities.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class EmailService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final RestClient restClient;
    private final String toEmail;
    private final String fromEmail;
    private final String fromName;
    private final String replyToEmail;
    private final String brevoApiKey;
    private final String frontendUrl;

    public EmailService(
            RestClient.Builder restClientBuilder,
            @Value("${app.mail.to}") String toEmail,
            @Value("${app.mail.from}") String fromEmail,
            @Value("${app.mail.from-name:Proyecto VA}") String fromName,
            @Value("${app.mail.reply-to:}") String replyToEmail,
            @Value("${app.mail.brevo.api-key:}") String brevoApiKey,
            @Value("${app.frontend.url}") String frontendUrl
    ) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.brevo.com")
                .build();
        this.toEmail = toEmail;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.replyToEmail = StringUtils.hasText(replyToEmail) ? replyToEmail : fromEmail;
        this.brevoApiKey = brevoApiKey;
        this.frontendUrl = frontendUrl;
    }

    @Async
    public void sendContactMessage(ContactMessageDTO dto) {
        if (shouldSkipDelivery("contact")) {
            return;
        }

        if (!StringUtils.hasText(toEmail)) {
            logger.warn("Skipping contact email because APP_MAIL_TO/app.mail.to is not configured");
            return;
        }

        sendEmail(
                List.of(new BrevoAddress(toEmail, null)),
                new BrevoAddress(dto.email(), dto.name()),
                "Nueva consulta - Viajes Pagos",
                buildHtmlBody(dto),
                "contact",
                toEmail
        );
    }

    public boolean isDeliveryConfigured() {
        return StringUtils.hasText(brevoApiKey) && StringUtils.hasText(fromEmail);
    }

    @Async
    public void sendInstallmentReminder(
            String userEmail,
            String userName,
            List<InstallmentReminderMailItem> items
    ) {
        if (items == null || items.isEmpty()) {
            return;
        }

        if (shouldSkipDelivery("installment reminder")) {
            return;
        }

        sendEmail(
                List.of(new BrevoAddress(userEmail, userName)),
                new BrevoAddress(replyToEmail, fromName),
                buildReminderSubject(items),
                buildInstallmentReminderHtmlBody(userName, items),
                "installment reminder",
                userEmail
        );
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String userName, String token) {
        if (shouldSkipDelivery("password reset")) {
            return;
        }

        String link = frontendUrl + "/reset-password?token=" + token;
        String safeName = escapeHtml(userName != null ? userName : "");
        String safeLink = escapeHtml(link);

        String html = """
                <div style="font-family:Segoe UI,Arial,sans-serif;
                            line-height:1.5;color:#0f2f57;">
                  <h2 style="margin:0 0 12px;">Recuperación de contraseña</h2>
                  <p style="margin:0 0 16px;">Hola %s,</p>
                  <p style="margin:0 0 16px;">
                    Recibimos una solicitud para restablecer la contraseña
                    de tu cuenta. Hacé click en el botón para continuar.
                  </p>
                  <a href="%s"
                     style="display:inline-block;padding:12px 24px;
                            background:#0b5bcf;color:#fff;border-radius:10px;
                            text-decoration:none;font-weight:700;
                            margin-bottom:16px;">
                    Restablecer contraseña
                  </a>
                  <p style="margin:0 0 8px;color:#526883;font-size:14px;">
                    Este enlace expira en 1 hora.
                  </p>
                  <p style="margin:0;color:#526883;font-size:14px;">
                    Si no solicitaste esto, ignorá este email.
                   Tu contraseña no fue modificada.
                  </p>
                </div>
                """.formatted(safeName, safeLink);

        sendEmail(
                List.of(new BrevoAddress(toEmail, userName)),
                new BrevoAddress(replyToEmail, fromName),
                "Recuperación de contraseña - ProyectoVA",
                html,
                "password reset",
                toEmail
        );
    }

    private boolean shouldSkipDelivery(String emailType) {
        if (isDeliveryConfigured()) {
            return false;
        }

        logger.warn(
                "Skipping {} email because Brevo delivery is not fully configured. Missing settings: {}",
                emailType,
                describeMissingDeliverySettings()
        );
        return true;
    }

    private String describeMissingDeliverySettings() {
        List<String> missingSettings = new java.util.ArrayList<>();

        if (!StringUtils.hasText(brevoApiKey)) {
            missingSettings.add("BREVO_API_KEY/app.mail.brevo.api-key");
        }
        if (!StringUtils.hasText(fromEmail)) {
            missingSettings.add("BREVO_FROM_EMAIL/app.mail.from");
        }

        return missingSettings.isEmpty() ? "none" : String.join(", ", missingSettings);
    }

    private void sendEmail(
            List<BrevoAddress> to,
            BrevoAddress replyTo,
            String subject,
            String htmlContent,
            String emailType,
            String recipientLog
    ) {
        BrevoSendEmailRequest payload = new BrevoSendEmailRequest(
                new BrevoAddress(fromEmail, fromName),
                to,
                replyTo,
                subject,
                htmlContent
        );

        try {
            restClient.post()
                    .uri("/v3/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("api-key", brevoApiKey)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            logger.error(
                    "Could not send {} email to {}. Brevo responded with status {} and body {}",
                    emailType,
                    recipientLog,
                    exception.getStatusCode(),
                    exception.getResponseBodyAsString(StandardCharsets.UTF_8),
                    exception
            );
            throw new IllegalStateException("Could not send " + emailType + " email", exception);
        } catch (Exception exception) {
            logger.error("Could not send {} email to {}", emailType, recipientLog, exception);
            throw new IllegalStateException("Could not send " + emailType + " email", exception);
        }
    }

    private String buildHtmlBody(ContactMessageDTO dto) {
        String safeName = escapeHtml(dto.name());
        String safeEmail = escapeHtml(dto.email());
        String safeMessage = escapeHtml(dto.message()).replace("\n", "<br/>");

        return """
                <div style="font-family:Segoe UI,Arial,sans-serif;line-height:1.5;color:#0f2f57;">
                  <h2 style="margin:0 0 12px;">Nueva consulta</h2>
                  <p style="margin:0 0 8px;"><strong>Nueva consulta de:</strong> %s</p>
                  <p style="margin:0 0 16px;"><strong>Responder a:</strong> <a href="mailto:%s">%s</a></p>
                  <div style="padding:12px 14px;border:1px solid #d8e2f0;border-radius:10px;background:#f8fbff;">
                    <p style="margin:0;"><strong>Mensaje:</strong></p>
                    <p style="margin:6px 0 0;">%s</p>
                  </div>
                </div>
                """.formatted(safeName, safeEmail, safeEmail, safeMessage);
    }

    private String buildInstallmentReminderHtmlBody(String userName, List<InstallmentReminderMailItem> items) {
        String safeName = escapeHtml(StringUtils.hasText(userName) ? userName : "Hola");

        List<InstallmentReminderMailItem> dueSoonItems = items.stream()
                .filter(item -> item.kind() == ReminderKind.DUE_SOON)
                .toList();
        List<InstallmentReminderMailItem> overdueItems = items.stream()
                .filter(item -> item.kind() == ReminderKind.OVERDUE)
                .toList();

        return """
                <div style="font-family:Segoe UI,Arial,sans-serif;line-height:1.5;color:#0f2f57;">
                  <h2 style="margin:0 0 12px;">Recordatorio de cuotas</h2>
                  <p style="margin:0 0 16px;">Hola %s, estas son las cuotas de tu viaje que requieren atencion.</p>
                  %s
                  %s
                  <p style="margin:18px 0 0;color:#526883;font-size:14px;">
                    Si ya realizaste el pago, puedes ingresar al panel y reportar el comprobante para su revision.
                  </p>
                </div>
                """.formatted(
                safeName,
                buildReminderSection(
                        "Cuotas proximas a vencer",
                        "#fff7e6",
                        "#92400e",
                        dueSoonItems
                ),
                buildReminderSection(
                        "Cuotas vencidas o pendientes",
                        "#fff1f2",
                        "#b91c1c",
                        overdueItems
                )
        );
    }

    private String buildReminderSection(
            String title,
            String background,
            String accentColor,
            List<InstallmentReminderMailItem> items
    ) {
        if (items.isEmpty()) {
            return "";
        }

        String cards = items.stream()
                .map(this::buildReminderCard)
                .reduce("", String::concat);

        return """
                <section style="margin:0 0 16px;">
                  <h3 style="margin:0 0 10px;color:%s;">%s</h3>
                  <div style="display:grid;gap:10px;">
                    %s
                  </div>
                </section>
                """.formatted(accentColor, escapeHtml(title), cards);
    }

    private String buildReminderCard(InstallmentReminderMailItem item) {
        return """
                <div style="padding:12px 14px;border:1px solid #d8e2f0;border-radius:10px;background:%s;">
                  <p style="margin:0 0 6px;"><strong>%s</strong> · Cuota %d</p>
                  <p style="margin:0 0 4px;">Saldo pendiente: <strong>%s</strong></p>
                  <p style="margin:0 0 4px;">Vencimiento: %s</p>
                  <p style="margin:0;"><strong>%s</strong></p>
                </div>
                """.formatted(
                item.kind() == ReminderKind.OVERDUE ? "#fff1f2" : "#fff7e6",
                escapeHtml(item.tripName()),
                item.installmentNumber(),
                escapeHtml(formatCurrency(item.remainingAmount(), item.currency())),
                escapeHtml(item.dueDate().format(DATE_FORMATTER)),
                item.kind() == ReminderKind.OVERDUE ? "Estado: vencida" : "Estado: proxima a vencer"
        );
    }

    private String buildReminderSubject(List<InstallmentReminderMailItem> items) {
        long dueSoonCount = items.stream().filter(item -> item.kind() == ReminderKind.DUE_SOON).count();
        long overdueCount = items.stream().filter(item -> item.kind() == ReminderKind.OVERDUE).count();

        if (dueSoonCount > 0 && overdueCount > 0) {
            return "Recordatorio de cuotas: " + dueSoonCount + " por vencer y " + overdueCount + " vencidas";
        }
        if (overdueCount > 0) {
            return "Recordatorio de cuotas vencidas: " + overdueCount;
        }
        return "Recordatorio de cuotas proximas a vencer: " + dueSoonCount;
    }

    private String formatCurrency(java.math.BigDecimal amount, Currency currency) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(new Locale("es", "AR"));
        formatter.setCurrency(java.util.Currency.getInstance(currency.name()));
        formatter.setMinimumFractionDigits(2);
        formatter.setMaximumFractionDigits(2);
        return formatter.format(amount);
    }

    private String escapeHtml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public enum ReminderKind {
        DUE_SOON,
        OVERDUE
    }

    public record InstallmentReminderMailItem(
            String tripName,
            Integer installmentNumber,
            LocalDate dueDate,
            java.math.BigDecimal remainingAmount,
            Currency currency,
            ReminderKind kind
    ) {
    }

    private record BrevoSendEmailRequest(
            BrevoAddress sender,
            List<BrevoAddress> to,
            BrevoAddress replyTo,
            String subject,
            String htmlContent
    ) {
    }

    private record BrevoAddress(
            String email,
            String name
    ) {
    }
}
