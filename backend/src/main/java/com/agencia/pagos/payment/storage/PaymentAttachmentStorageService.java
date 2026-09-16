package com.agencia.pagos.payment.storage;

import org.springframework.web.multipart.MultipartFile;

public interface PaymentAttachmentStorageService {

    String storeReceipt(MultipartFile file, Long tripId, Long userId, Long studentId);

    String resolveFileReference(String storedValue);

    default boolean deleteReceipt(String storedValue) {
        return true;
    }
}
