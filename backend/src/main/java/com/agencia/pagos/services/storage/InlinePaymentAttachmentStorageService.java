package com.agencia.pagos.services.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(prefix = "app.storage.receipts", name = "provider", havingValue = "inline", matchIfMissing = true)
public class InlinePaymentAttachmentStorageService implements PaymentAttachmentStorageService {

    @Override
    public String storeReceipt(MultipartFile file, Long tripId, Long userId, Long studentId) {
        return PaymentAttachmentStorageSupport.toInlineDataUrl(file);
    }

    @Override
    public String resolveFileReference(String storedValue) {
        return storedValue == null ? "" : storedValue;
    }
}
