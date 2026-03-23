import { describe, expect, it } from "vitest";

import { isInstallmentDueSoon, resolveInstallmentBaseDisplay } from "@/lib/installment-status";

describe("installment-status helper", () => {
  const today = new Date("2026-03-23T12:00:00");

  it("marca Vence pronto cuando la cuota YELLOW entra en yellowWarningDays", () => {
    expect(resolveInstallmentBaseDisplay("YELLOW", "2026-03-25", 3, today)).toEqual({
      tone: "yellow",
      label: "Vence pronto",
    });
    expect(isInstallmentDueSoon("2026-03-25", 3, today)).toBe(true);
  });

  it("mantiene Al dia cuando la cuota YELLOW queda fuera de yellowWarningDays", () => {
    expect(resolveInstallmentBaseDisplay("YELLOW", "2026-04-05", 3, today)).toEqual({
      tone: "green",
      label: "Al día",
    });
    expect(isInstallmentDueSoon("2026-04-05", 3, today)).toBe(false);
  });

  it("no altera el copy de cuotas RED", () => {
    expect(resolveInstallmentBaseDisplay("RED", "2026-03-20", 5, today)).toEqual({
      tone: "red",
      label: "Vencida",
    });
  });

  it("no altera el copy de cuotas RETROACTIVE", () => {
    expect(resolveInstallmentBaseDisplay("RETROACTIVE", "2026-03-20", 5, today)).toEqual({
      tone: "retro",
      label: "Deuda retroactiva",
    });
  });
});
