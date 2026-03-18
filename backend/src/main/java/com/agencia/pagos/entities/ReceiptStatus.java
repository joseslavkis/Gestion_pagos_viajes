package com.agencia.pagos.entities;

public enum ReceiptStatus {
    PENDING,  // El usuario lo subió, pero el admin no lo vio
    APPROVED, // El pago fue validado y impacta en el sistema
    REJECTED  // El comprobante no es válido (ej. foto borrosa)
}