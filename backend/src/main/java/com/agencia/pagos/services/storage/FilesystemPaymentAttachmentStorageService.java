package com.agencia.pagos.services.storage;

import com.agencia.pagos.config.storage.PaymentAttachmentStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "app.storage.receipts", name = "provider", havingValue = "filesystem")
public class FilesystemPaymentAttachmentStorageService implements PaymentAttachmentStorageService {

    public record AttachmentDownload(Path path, MediaType mediaType, String filename) {
    }

    private final PaymentAttachmentStorageProperties properties;
    private final PaymentAttachmentUrlTokenService tokenService;
    private final Path basePath;
    private final String publicBaseUrl;

    public FilesystemPaymentAttachmentStorageService(
            PaymentAttachmentStorageProperties properties,
            PaymentAttachmentUrlTokenService tokenService
    ) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.basePath = initializeBasePath(properties.getFilesystem().getBasePath());
        this.publicBaseUrl = normalizePublicBaseUrl(properties.getFilesystem().getPublicBaseUrl());
    }

    @Override
    public String storeReceipt(MultipartFile file, Long tripId, Long userId, Long studentId) {
        PaymentAttachmentStorageSupport.validate(file);
        if (file == null || file.isEmpty()) {
            return "";
        }

        String storedValue = buildStoredValue(file, tripId, userId, studentId);
        Path absolutePath = resolveAbsolutePath(storedValue);

        try {
            Files.createDirectories(absolutePath.getParent());
            Files.copy(file.getInputStream(), absolutePath, StandardCopyOption.REPLACE_EXISTING);
            return storedValue;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo guardar el archivo adjunto en el filesystem del servidor", exception);
        }
    }

    @Override
    public String resolveFileReference(String storedValue) {
        if (!StringUtils.hasText(storedValue)) {
            return "";
        }
        if (PaymentAttachmentStorageSupport.isInlineDataUrl(storedValue) || isRemoteUrl(storedValue)) {
            return storedValue;
        }

        String normalizedStoredValue = normalizeStoredValue(storedValue);
        String token = tokenService.createToken(
                normalizedStoredValue,
                properties.getFilesystem().getUrlExpirationMinutes()
        );
        String filename = Path.of(normalizedStoredValue).getFileName().toString();

        return publicBaseUrl + "/api/v1/payment-attachments/" + token + "/" + filename;
    }

    public Optional<AttachmentDownload> loadReceipt(String token) {
        return tokenService.extractStoredValue(token)
                .flatMap(this::loadReceiptByStoredValue);
    }

    private Optional<AttachmentDownload> loadReceiptByStoredValue(String storedValue) {
        try {
            Path absolutePath = resolveAbsolutePath(storedValue);
            if (!Files.exists(absolutePath) || !Files.isRegularFile(absolutePath) || !Files.isReadable(absolutePath)) {
                return Optional.empty();
            }

            String filename = absolutePath.getFileName().toString();
            MediaType mediaType = resolveMediaType(absolutePath, filename);
            return Optional.of(new AttachmentDownload(absolutePath, mediaType, filename));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private MediaType resolveMediaType(Path absolutePath, String filename) {
        try {
            String detectedType = Files.probeContentType(absolutePath);
            if (StringUtils.hasText(detectedType)) {
                return MediaType.parseMediaType(detectedType);
            }
        } catch (IOException ignored) {
        }

        return MediaTypeFactory.getMediaType(filename)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    private Path initializeBasePath(String configuredBasePath) {
        if (!StringUtils.hasText(configuredBasePath)) {
            throw new IllegalStateException("Debe configurar RECEIPTS_FILESYSTEM_BASE_PATH cuando RECEIPTS_STORAGE_PROVIDER=filesystem");
        }

        try {
            Path resolvedBasePath = Path.of(configuredBasePath).toAbsolutePath().normalize();
            Files.createDirectories(resolvedBasePath);
            return resolvedBasePath;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo inicializar el directorio base para comprobantes", exception);
        }
    }

    private String normalizePublicBaseUrl(String configuredPublicBaseUrl) {
        if (!StringUtils.hasText(configuredPublicBaseUrl)) {
            throw new IllegalStateException("Debe configurar BACKEND_PUBLIC_URL o app.storage.receipts.filesystem.public-base-url cuando RECEIPTS_STORAGE_PROVIDER=filesystem");
        }
        return configuredPublicBaseUrl.endsWith("/")
                ? configuredPublicBaseUrl.substring(0, configuredPublicBaseUrl.length() - 1)
                : configuredPublicBaseUrl;
    }

    private Path resolveAbsolutePath(String storedValue) {
        String normalizedStoredValue = normalizeStoredValue(storedValue);
        Path resolvedPath = basePath.resolve(normalizedStoredValue).normalize();
        if (!resolvedPath.startsWith(basePath)) {
            throw new IllegalArgumentException("Referencia de archivo inválida");
        }
        return resolvedPath;
    }

    private String normalizeStoredValue(String storedValue) {
        if (!StringUtils.hasText(storedValue)) {
            throw new IllegalArgumentException("La referencia del archivo es obligatoria");
        }

        String sanitized = storedValue.replace('\\', '/');
        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }

        Path normalizedPath = Path.of(sanitized).normalize();
        if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..")) {
            throw new IllegalArgumentException("La referencia del archivo es inválida");
        }

        String normalized = normalizedPath.toString().replace('\\', '/');
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("La referencia del archivo es inválida");
        }
        return normalized;
    }

    private String buildStoredValue(MultipartFile file, Long tripId, Long userId, Long studentId) {
        String prefix = normalizePrefix(properties.getFilesystem().getPathPrefix());
        String extension = PaymentAttachmentStorageSupport.resolveExtension(file);
        StringBuilder builder = new StringBuilder(prefix)
                .append("/trip-").append(tripId)
                .append("/user-").append(userId);

        if (studentId != null) {
            builder.append("/student-").append(studentId);
        }

        return builder.append("/")
                .append(UUID.randomUUID())
                .append(extension)
                .toString();
    }

    private String normalizePrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return "receipts";
        }
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private boolean isRemoteUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
