package com.agencia.pagos.controllers;

import com.agencia.pagos.services.storage.FilesystemPaymentAttachmentStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/payment-attachments")
@ConditionalOnProperty(prefix = "app.storage.receipts", name = "provider", havingValue = "filesystem")
class PaymentAttachmentRestController {

    private final FilesystemPaymentAttachmentStorageService paymentAttachmentStorageService;

    PaymentAttachmentRestController(FilesystemPaymentAttachmentStorageService paymentAttachmentStorageService) {
        this.paymentAttachmentStorageService = paymentAttachmentStorageService;
    }

    @GetMapping("/{token}/{filename:.+}")
    ResponseEntity<Resource> getAttachment(
            @PathVariable String token,
            @PathVariable String filename
    ) {
        FilesystemPaymentAttachmentStorageService.AttachmentDownload download =
                paymentAttachmentStorageService.loadReceipt(token)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(download.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .contentType(download.mediaType())
                .body(new FileSystemResource(download.path()));
    }
}
