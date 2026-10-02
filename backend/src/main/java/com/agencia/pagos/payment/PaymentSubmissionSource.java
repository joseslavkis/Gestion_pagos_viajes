package com.agencia.pagos.payment;

/**
 * Origen explícito de un {@link PaymentSubmission}.
 *
 * <p>CUSTOMER_SUBMISSION es un pago informado por el cliente que pasa por
 * PENDING → revisión administrativa. ADMIN_MANUAL es una imputación manual
 * ejecutada directamente por un administrador: nunca pasa por PENDING y
 * acredita saldo de forma atómica.
 */
public enum PaymentSubmissionSource {
    CUSTOMER_SUBMISSION,
    ADMIN_MANUAL
}
