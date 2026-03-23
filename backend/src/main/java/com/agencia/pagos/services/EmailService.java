package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.ContactMessageDTO;
import com.agencia.pagos.entities.Currency;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class EmailService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JavaMailSender mailSender;
    private final String toEmail;
    private final String fromEmail;
    private final String smtpUsername;
    private final String smtpPassword;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.to}") String toEmail,
            @Value("${app.mail.from}") String fromEmail,
            @Value("${spring.mail.username:}") String smtpUsername,
            @Value("${spring.mail.password:}") String smtpPassword
    ) {
        this.mailSender = mailSender;
        this.toEmail = toEmail;
        this.fromEmail = fromEmail;
        this.smtpUsername = smtpUsername;
        this.smtpPassword = smtpPassword;
    }

    @Async
    public void sendContactMessage(ContactMessageDTO dto) {
        MimeMessage mimeMessage = mailSender.createMimeMessage();

        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage,
                    MimeMessageHelper.MULTIPART_MODE_NO,
                    StandardCharsets.UTF_8.name()
            );

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Nueva consulta - Viajes Pagos");
            helper.setReplyTo(dto.email());

            String body = buildHtmlBody(dto);
            helper.setText(body, true);

            // Ensure headers are consistent
            mimeMessage.setHeader("X-Contact-Source", "landing");
            mimeMessage.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            // Async execution: best-effort. Surface via logs (Spring will log uncaught exceptions too).
            throw new IllegalStateException("Could not send contact email", e);
        }
    }

    public boolean isDeliveryConfigured() {
        return StringUtils.hasText(smtpUsername) && StringUtils.hasText(smtpPassword) && StringUtils.hasText(fromEmail);
    }

    @Async
    public void sendInstallmentReminder(
            String userEmail,
            String userName,
            List<InstallmentReminderMailItem> items
    ) {
        if (!isDeliveryConfigured() || items == null || items.isEmpty()) {
            return;
        }

        MimeMessage mimeMessage = mailSender.createMimeMessage();

        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage,
                    MimeMessageHelper.MULTIPART_MODE_NO,
                    StandardCharsets.UTF_8.name()
            );

            helper.setFrom(fromEmail);
            helper.setTo(userEmail);
            helper.setReplyTo(fromEmail);
            helper.setSubject(buildReminderSubject(items));
            helper.setText(buildInstallmentReminderHtmlBody(userName, items), true);

            mimeMessage.setHeader("X-Notification-Type", "installment-reminder");
            mimeMessage.setRecipient(Message.RecipientType.TO, new InternetAddress(userEmail));

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send installment reminder email", e);
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
}
