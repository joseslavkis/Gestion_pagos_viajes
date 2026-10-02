package com.agencia.pagos.payment;

import org.springframework.http.HttpStatus;

/**
 * Error de negocio del flujo de imputación manual administrativa.
 * Lleva un código estable para que el frontend decida comportamiento
 * sin mostrarlo, y un mensaje en español apto para UI.
 */
public class ManualImputationException extends RuntimeException {

    public enum Code {
        PENDING_REVIEW,
        INVALID_ANCHOR,
        ALREADY_PAID,
        TRIP_FULLY_PAID,
        AMOUNT_EXCEEDS_BALANCE,
        STALE_BALANCE,
        PREVIEW_EXPIRED,
        PREVIEW_MISMATCH,
        QUOTE_UNAVAILABLE
    }

    private final Code code;
    private final HttpStatus httpStatus;

    public ManualImputationException(Code code, String message, HttpStatus httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public Code getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public static ManualImputationException pendingReview() {
        return new ManualImputationException(
                Code.PENDING_REVIEW,
                "Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación.",
                HttpStatus.CONFLICT);
    }

    public static ManualImputationException invalidAnchor(int firstPayableNumber) {
        return new ManualImputationException(
                Code.INVALID_ANCHOR,
                "La imputación debe comenzar desde la cuota #" + firstPayableNumber
                        + ", que es la primera cuota pendiente de pago.",
                HttpStatus.CONFLICT);
    }

    public static ManualImputationException invalidAnchorGeneric() {
        return new ManualImputationException(
                Code.INVALID_ANCHOR,
                "La imputación debe comenzar desde la primera cuota pendiente de pago.",
                HttpStatus.CONFLICT);
    }

    public static ManualImputationException alreadyPaid(Integer firstPayableNumber) {
        if (firstPayableNumber != null) {
            return new ManualImputationException(
                    Code.ALREADY_PAID,
                    "Esta cuota ya está completamente pagada. Seleccioná la primera cuota pendiente de pago (#"
                            + firstPayableNumber + ").",
                    HttpStatus.CONFLICT);
        }
        return new ManualImputationException(
                Code.ALREADY_PAID,
                "Esta cuota ya está completamente pagada.",
                HttpStatus.CONFLICT);
    }

    public static ManualImputationException tripFullyPaid() {
        return new ManualImputationException(
                Code.TRIP_FULLY_PAID,
                "Este viaje no tiene saldo pendiente.",
                HttpStatus.CONFLICT);
    }

    public static ManualImputationException amountExceedsBalance() {
        return new ManualImputationException(
                Code.AMOUNT_EXCEEDS_BALANCE,
                "El monto ingresado supera el saldo pendiente del viaje.",
                HttpStatus.BAD_REQUEST);
    }

    public static ManualImputationException staleBalance() {
        return new ManualImputationException(
                Code.STALE_BALANCE,
                "El saldo cambió desde la última previsualización. Actualizá la imputación e intentá nuevamente.",
                HttpStatus.CONFLICT);
    }

    public static ManualImputationException previewExpired() {
        return new ManualImputationException(
                Code.PREVIEW_EXPIRED,
                "La previsualización venció. Volvé a calcular la imputación.",
                HttpStatus.BAD_REQUEST);
    }

    public static ManualImputationException previewMismatch() {
        return new ManualImputationException(
                Code.PREVIEW_MISMATCH,
                "La previsualización no corresponde a los datos ingresados. Volvé a calcular la imputación.",
                HttpStatus.BAD_REQUEST);
    }

    public static ManualImputationException quoteUnavailable() {
        return new ManualImputationException(
                Code.QUOTE_UNAVAILABLE,
                "No se pudo obtener la cotización para la fecha seleccionada. Intentá nuevamente.",
                HttpStatus.BAD_GATEWAY);
    }
}
