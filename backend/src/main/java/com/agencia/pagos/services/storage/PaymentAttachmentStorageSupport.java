package com.agencia.pagos.services.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PaymentAttachmentStorageSupport {

    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf"
    );

    private static final Map<String, String> EXTENSIONS_BY_MIME = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "application/pdf", ".pdf"
    );

    private PaymentAttachmentStorageSupport() {
    }

    static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }

        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Solo se aceptan imágenes JPG, PNG, WEBP o archivos PDF");
        }

        long maxBytes = 5L * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("El archivo no puede superar los 5MB");
        }
    }

    static String toInlineDataUrl(MultipartFile file) {
        validate(file);
        if (file == null || file.isEmpty()) {
            return "";
        }

        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            return "data:" + file.getContentType() + ";base64," + base64;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo procesar el archivo adjunto", exception);
        }
    }

    static String resolveExtension(MultipartFile file) {
        validate(file);

        String mimeType = file != null ? file.getContentType() : null;
        if (mimeType != null && EXTENSIONS_BY_MIME.containsKey(mimeType)) {
            return EXTENSIONS_BY_MIME.get(mimeType);
        }

        String originalFilename = file != null ? file.getOriginalFilename() : null;
        if (originalFilename == null || originalFilename.isBlank()) {
            return ".bin";
        }

        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            return ".bin";
        }

        return originalFilename.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    static boolean isInlineDataUrl(String value) {
        return value != null && value.startsWith("data:");
    }
}
