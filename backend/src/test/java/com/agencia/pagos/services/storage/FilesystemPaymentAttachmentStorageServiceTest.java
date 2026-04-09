package com.agencia.pagos.services.storage;

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

class FilesystemPaymentAttachmentStorageServiceTest {

    @TempDir
    Path tempDir;

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
