package com.agencia.pagos.payment.storage;

import com.agencia.pagos.config.storage.PaymentAttachmentStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilesystemPaymentAttachmentStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsUnsupportedMimeAndFilesLargerThanFiveMb() {
        PaymentAttachmentStorageProperties properties = new PaymentAttachmentStorageProperties();
        properties.getFilesystem().setBasePath(tempDir.toString());
        properties.getFilesystem().setPublicBaseUrl("http://localhost:8080");
        var storage = new FilesystemPaymentAttachmentStorageService(properties,
                new PaymentAttachmentUrlTokenService(Base64.getEncoder().encodeToString(
                        "cleanup-test-secret-cleanup-test-secret".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> storage.storeReceipt(
                new MockMultipartFile("files", "bad.txt", "text/plain", new byte[]{1}), 1L, 2L, null));
        assertThrows(IllegalArgumentException.class, () -> storage.storeReceipt(
                new MockMultipartFile("files", "big.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1]), 1L, 2L, null));
    }

    @Test
    void deleteReceipt_removesStoredFileAndEmptyDirectories() throws Exception {
        PaymentAttachmentStorageProperties properties = new PaymentAttachmentStorageProperties();
        properties.setProvider(PaymentAttachmentStorageProperties.Provider.FILESYSTEM);
        properties.getFilesystem().setBasePath(tempDir.toString());
        properties.getFilesystem().setPublicBaseUrl("http://localhost:8080");

        PaymentAttachmentUrlTokenService tokenService = new PaymentAttachmentUrlTokenService(
                Base64.getEncoder()
                        .encodeToString("cleanup-test-secret-cleanup-test-secret".getBytes(StandardCharsets.UTF_8))
        );

        FilesystemPaymentAttachmentStorageService storageService = new FilesystemPaymentAttachmentStorageService(
                properties,
                tokenService
        );

        String storedValue = storageService.storeReceipt(
                new MockMultipartFile("file", "receipt.png", "image/png", "png".getBytes(StandardCharsets.UTF_8)),
                1L,
                2L,
                3L
        );

        Path storedPath = tempDir.resolve(storedValue);
        assertTrue(Files.exists(storedPath));

        boolean deleted = storageService.deleteReceipt(storedValue);

        assertTrue(deleted);
        assertFalse(Files.exists(storedPath));
        assertFalse(Files.exists(storedPath.getParent()));
    }
}
