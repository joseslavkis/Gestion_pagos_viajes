package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.services.storage.PaymentAttachmentStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com",
        "app.storage.receipts.provider=filesystem",
        "app.storage.receipts.filesystem.public-base-url=https://backend.example"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentAttachmentRestControllerTest extends ControllerIntegrationTestSupport {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerFilesystemProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.receipts.filesystem.base-path", () -> tempDir.toString());
    }

    @MockBean
    private JavaMailSender javaMailSender;

    @Autowired
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @Test
    void getAttachment_conTokenValidoDevuelveArchivoSinAutenticacion() throws Exception {
        String storedValue = paymentAttachmentStorageService.storeReceipt(
                new MockMultipartFile("file", "comprobante.jpg", "image/jpeg", "contenido".getBytes()),
                10L,
                20L,
                30L
        );

        String publicUrl = paymentAttachmentStorageService.resolveFileReference(storedValue);
        URI attachmentUri = URI.create(publicUrl);

        mockMvc.perform(get(attachmentUri.getRawPath()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes("contenido".getBytes()));
    }
}
