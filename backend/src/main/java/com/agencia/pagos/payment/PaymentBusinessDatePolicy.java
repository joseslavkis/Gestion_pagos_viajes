package com.agencia.pagos.payment;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class PaymentBusinessDatePolicy {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final Clock clock;

    public PaymentBusinessDatePolicy(@Qualifier("paymentBusinessClock") Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    public void requireNotFuture(LocalDate paymentDate) {
        if (paymentDate == null) {
            throw new IllegalArgumentException("La fecha de pago informada es obligatoria");
        }
        LocalDate today = today();
        if (paymentDate.isAfter(today)) {
            throw new IllegalArgumentException("La fecha de pago no puede ser futura (hoy es " + today + ")");
        }
    }
}
