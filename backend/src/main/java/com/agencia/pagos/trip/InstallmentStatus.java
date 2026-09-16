package com.agencia.pagos.trip;

public enum InstallmentStatus {
    GREEN,
    YELLOW,
    RED,
    /** Cuota de período pasado generada retroactivamente cuando un usuario se suma tarde al viaje. */
    RETROACTIVE
}
