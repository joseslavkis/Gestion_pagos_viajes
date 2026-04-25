export type InstallmentAmountPlan = {
  firstInstallmentAmount: number;
  remainingInstallmentAmount: number | null;
  installmentsCount: number;
};

export function normalizeMoneyAmount(amount: number): number {
  return Math.round((amount + Number.EPSILON) * 100) / 100;
}

export function roundInstallmentAmountUp(amount: number): number {
  return Math.ceil(amount);
}

export function calculateInstallmentAmountPlan(
  totalAmount: number,
  firstInstallmentAmount: number,
  installmentsCount: number,
): InstallmentAmountPlan {
  validateInstallmentAmountInputs(totalAmount, firstInstallmentAmount, installmentsCount);

  const normalizedFirstInstallmentAmount = normalizeMoneyAmount(firstInstallmentAmount);
  if (installmentsCount === 1) {
    return {
      firstInstallmentAmount: normalizedFirstInstallmentAmount,
      remainingInstallmentAmount: null,
      installmentsCount,
    };
  }

  const remainingAmount = normalizeMoneyAmount(totalAmount) - normalizedFirstInstallmentAmount;
  return {
    firstInstallmentAmount: normalizedFirstInstallmentAmount,
    remainingInstallmentAmount: roundInstallmentAmountUp(remainingAmount / (installmentsCount - 1)),
    installmentsCount,
  };
}

export function validateInstallmentAmountInputs(
  totalAmount: number,
  firstInstallmentAmount: number,
  installmentsCount: number,
): void {
  if (!Number.isFinite(totalAmount) || totalAmount <= 0) {
    throw new Error("El monto total del viaje debe ser mayor a cero.");
  }
  if (!Number.isFinite(firstInstallmentAmount) || firstInstallmentAmount <= 0) {
    throw new Error("La primera cuota debe ser mayor a cero.");
  }
  if (!Number.isInteger(installmentsCount) || installmentsCount < 1) {
    throw new Error("La cantidad de cuotas debe ser mayor a cero.");
  }

  const normalizedTotalAmount = normalizeMoneyAmount(totalAmount);
  const normalizedFirstInstallmentAmount = normalizeMoneyAmount(firstInstallmentAmount);

  if (normalizedFirstInstallmentAmount > normalizedTotalAmount) {
    throw new Error("La primera cuota no puede superar el monto total del viaje.");
  }
  if (installmentsCount > 1 && normalizedFirstInstallmentAmount >= normalizedTotalAmount) {
    throw new Error("La primera cuota debe ser menor al monto total cuando el viaje tiene más de una cuota.");
  }
}
