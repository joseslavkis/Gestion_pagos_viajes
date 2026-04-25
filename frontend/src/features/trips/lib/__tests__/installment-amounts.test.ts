import { describe, expect, it } from "vitest";

import { calculateInstallmentAmountPlan } from "@/features/trips/lib/installment-amounts";

describe("calculateInstallmentAmountPlan", () => {
  it("calcula primera cuota custom y redondea hacia arriba todas las restantes", () => {
    expect(calculateInstallmentAmountPlan(1000, 300, 4)).toEqual({
      firstInstallmentAmount: 300,
      remainingInstallmentAmount: 234,
      installmentsCount: 4,
    });
  });

  it("no compensa centavos en la ultima cuota", () => {
    expect(calculateInstallmentAmountPlan(100, 10, 4)).toEqual({
      firstInstallmentAmount: 10,
      remainingInstallmentAmount: 30,
      installmentsCount: 4,
    });
  });

  it("rechaza primera cuota mayor al monto total", () => {
    expect(() => calculateInstallmentAmountPlan(100, 100.01, 1)).toThrow(
      "La primera cuota no puede superar el monto total del viaje.",
    );
  });

  it("rechaza primera cuota igual al total cuando hay mas de una cuota", () => {
    expect(() => calculateInstallmentAmountPlan(100, 100, 2)).toThrow(
      "La primera cuota debe ser menor al monto total cuando el viaje tiene más de una cuota.",
    );
  });
});
