import {
  compareNonNegativeDecimalStrings,
  normalizePaymentMoneyInput,
} from "./decimal-strings";
import type { DecimalString } from "./payments-dtos";

/**
 * Estado puro de la quick action "Completar cuota actual".
 * Testeable sin React: decide si el monto actual sigue representando
 * exactamente el saldo a completar.
 */
export function shouldKeepCompleteAnchorChecked(
  checked: boolean,
  currentAmount: string,
  completedAmount: DecimalString | null,
): boolean {
  if (!checked) return false;
  if (completedAmount == null) return false;
  const normalized = normalizePaymentMoneyInput(currentAmount);
  if (normalized == null) return false;
  return compareNonNegativeDecimalStrings(normalized, completedAmount) === 0;
}

export function isValidMoneyInput(value: string): boolean {
  return normalizePaymentMoneyInput(value) != null;
}

export function todayIsoDate(timeZone = "America/Argentina/Buenos_Aires"): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone,
  }).formatToParts(new Date());
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}`;
}

export function formatDecimalMoney(amount: DecimalString | string, currency: string): string {
  try {
    return new Intl.NumberFormat("es-AR", {
      style: "currency",
      currency,
    }).format(Number.parseFloat(amount));
  } catch {
    return `${amount} ${currency}`;
  }
}

/**
 * Cotización para presentación (solo display, nunca decide negocio).
 * es-AR con hasta 8 decimales (tope del contrato de tasas del backend).
 */
export function formatRateEs(rate: DecimalString | string): string {
  try {
    return new Intl.NumberFormat("es-AR", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 8,
    }).format(Number.parseFloat(rate));
  } catch {
    return rate;
  }
}
