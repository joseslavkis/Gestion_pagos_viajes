package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.ContactMessageDTO;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String toEmail;
    private final String fromEmail;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.to}") String toEmail,
            @Value("${app.mail.from}") String fromEmail
    ) {
        this.mailSender = mailSender;
        this.toEmail = toEmail;
        this.fromEmail = fromEmail;
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

    private String escapeHtml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

