import { normalizePaymentMoneyInput } from "./decimal-strings";
import type { Currency } from "./payments-dtos";

export type ReceiptAmount = { id: number; file: File; currency: Currency; amount: string };

export const MAX_MONEY_CENTS = 9999999999n; // PaymentSubmission is decimal(10,2).

/**
 * Single source of truth for the domain-wide monetary ceiling (99,999,999.99).
 * Compares with bigint cents; never float.
 */
export function exceedsMaxMoney(amount: string): boolean {
  const normalized = normalizePaymentMoneyInput(amount);
  if (normalized == null) return false;
  const [integer, fraction = ""] = normalized.split(".");
  const cents = BigInt(integer) * 100n + BigInt(fraction.padEnd(2, "0"));
  return cents > MAX_MONEY_CENTS;
}

export function moneyCents(value: string): bigint | null {
  const normalized = normalizePaymentMoneyInput(value);
  if (normalized == null) return null;
  const [integer, fraction = ""] = normalized.split(".");
  const cents = BigInt(integer) * 100n + BigInt(fraction.padEnd(2, "0"));
  return cents > 0n && cents <= MAX_MONEY_CENTS ? cents : null;
}

export function centsToMoney(cents: bigint): string | null {
  if (cents < 0n || cents > MAX_MONEY_CENTS) return null;
  return `${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
}

export function receiptSubtotals(receipts: ReceiptAmount[]): Record<Currency, string> | null {
  if (receipts.length < 1 || receipts.length > 5) return null;
  const sums = { ARS: 0n, USD: 0n };
  for (const receipt of receipts) {
    const cents = moneyCents(receipt.amount);
    if (cents == null || !(receipt.currency in sums)) return null;
    sums[receipt.currency] += cents;
  }
  const ARS = centsToMoney(sums.ARS);
  const USD = centsToMoney(sums.USD);
  return ARS != null && USD != null ? { ARS, USD } : null;
}
