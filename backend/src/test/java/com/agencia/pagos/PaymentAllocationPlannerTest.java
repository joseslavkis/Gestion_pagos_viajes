package com.agencia.pagos;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.services.PaymentAllocationPlanner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentAllocationPlannerTest {

    private final PaymentAllocationPlanner planner = new PaymentAllocationPlanner();

    @Test
    void plan_reparteMontoLibreSecuencialmenteConUltimaCuotaParcial() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "0.00"),
                buildInstallment(2, "100.00", "0.00"),
                buildInstallment(3, "100.00", "0.00")
        );

        PaymentAllocationPlanner.PlanResult result = planner.plan(
                installments,
                new BigDecimal("250.00"),
                Currency.ARS,
                null
        );

        assertEquals(new BigDecimal("300.00"), result.maxAllowedAmount());
        assertEquals(new BigDecimal("300.00"), result.totalPendingAmountInTripCurrency());
        assertEquals(new BigDecimal("250.00"), result.amountInTripCurrency());
        assertEquals(3, result.allocations().size());
        assertEquals(new BigDecimal("100.00"), result.allocations().get(0).amountInTripCurrency());
        assertEquals(new BigDecimal("100.00"), result.allocations().get(1).amountInTripCurrency());
        assertEquals(new BigDecimal("50.00"), result.allocations().get(2).amountInTripCurrency());
    }

    @Test
    void plan_respetaSaldoYaPagadoAntesDeImputar() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "40.00"),
                buildInstallment(2, "100.00", "0.00"),
                buildInstallment(3, "100.00", "0.00")
        );

        PaymentAllocationPlanner.PlanResult result = planner.plan(
                installments,
                new BigDecimal("160.00"),
                Currency.ARS,
                null
        );

        assertEquals(2, result.allocations().size());
        assertEquals(new BigDecimal("60.00"), result.allocations().get(0).amountInTripCurrency());
        assertEquals(new BigDecimal("100.00"), result.allocations().get(1).amountInTripCurrency());
    }

    @Test
    void plan_fallaSiElMontoInformadoSuperaElSaldoTotal() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "0.00"),
                buildInstallment(2, "100.00", "0.00")
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> planner.plan(installments, new BigDecimal("250.01"), Currency.ARS, null)
        );

        assertEquals("El monto informado supera el saldo pendiente total de esta inscripción", error.getMessage());
    }

    private Installment buildInstallment(int installmentNumber, String totalDue, String paidAmount) {
        Trip trip = new Trip();
        trip.setCurrency(Currency.ARS);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(LocalDate.now().plusDays(installmentNumber));
        installment.setCapitalAmount(new BigDecimal(totalDue));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setTotalDue(new BigDecimal(totalDue));
        installment.setPaidAmount(new BigDecimal(paidAmount));
        installment.setStatus(InstallmentStatus.YELLOW);
        return installment;
    }
}
