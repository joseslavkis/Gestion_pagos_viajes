package com.agencia.pagos.payment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentBusinessDatePolicyTest {

    @Test
    void futurePaymentDateUsesTheBuenosAiresBusinessDayFromTheInjectedClock() {
        PaymentBusinessDatePolicy policy = new PaymentBusinessDatePolicy(Clock.fixed(
                Instant.parse("2026-09-24T02:59:59Z"), ZoneOffset.UTC));

        assertEquals(LocalDate.of(2026, 9, 23), policy.today());
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireNotFuture(LocalDate.of(2026, 9, 24)));
    }

    @Test
    void paymentDateAtBusinessMidnightIsAccepted() {
        PaymentBusinessDatePolicy policy = new PaymentBusinessDatePolicy(Clock.fixed(
                Instant.parse("2026-09-24T03:00:00Z"), ZoneOffset.UTC));

        assertEquals(LocalDate.of(2026, 9, 24), policy.today());
        policy.requireNotFuture(LocalDate.of(2026, 9, 24));
    }
}
