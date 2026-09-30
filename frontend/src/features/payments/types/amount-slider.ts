import { normalizePaymentMoneyInput } from "./decimal-strings";
import { MAX_MONEY_CENTS } from "./receipt-amounts";
import type { DecimalString } from "./payments-dtos";

export type AmountCorrection = "up" | "down" | "none";

/**
 * The slider uses abstract discrete positions, never money, as its domain.
 * The physical center ALWAYS represents the reported amount (no correction).
 * Positions map to bigint cents with integer math only — no floats anywhere.
 */
export const SLIDER_MIN_POSITION = 0;
export const SLIDER_CENTER_POSITION = 500;
export const SLIDER_MAX_POSITION = 1000;

const POSITIONS_PER_SIDE = 500n;

export type SliderRange = {
  lowerCents: bigint;
  centerCents: bigint;
  upperCents: bigint;
};

/**
 * Parse a canonical decimal string into bigint cents.
 * Unlike moneyCents(), zero is allowed (left slider extreme) and no ceiling
 * is applied — callers decide validity with exceedsMaxMoney().
 */
export function decimalStringToCents(value: string): bigint | null {
  const normalized = normalizePaymentMoneyInput(value);
  if (normalized == null) {
    return null;
  }
  const [integer, fraction = ""] = normalized.split(".");
  return BigInt(integer) * 100n + BigInt(fraction.padEnd(2, "0"));
}

/**
 * Convenience range: left extreme 0, center the reported amount,
 * right extreme min(reported * 2, MAX_MONEY). The center never moves,
 * even when reported * 2 overflows the persistible ceiling.
 */
export function reportedSliderRange(reportedAmount: DecimalString): SliderRange {
  const parsed = decimalStringToCents(reportedAmount) ?? 0n;
  const center = parsed > MAX_MONEY_CENTS ? MAX_MONEY_CENTS : parsed;
  const doubled = center * 2n;
  return {
    lowerCents: 0n,
    centerCents: center,
    upperCents: doubled > MAX_MONEY_CENTS ? MAX_MONEY_CENTS : doubled,
  };
}

/**
 * Strategy for manual amounts beyond the convenience range: grow the visual
 * upper bound to include the typed value (capped at MAX_MONEY), keeping the
 * center pinned at the reported amount. Never shrinks the range.
 */
export function extendSliderUpper(range: SliderRange, manualCents: bigint): SliderRange {
  const capped = manualCents > MAX_MONEY_CENTS ? MAX_MONEY_CENTS : manualCents;
  if (capped <= range.upperCents) {
    return range;
  }
  return { ...range, upperCents: capped };
}

/**
 * Convert a slider position to bigint cents. Piecewise linear per side so the
 * center maps exactly to the reported amount and each extreme is exact.
 * Rounds to the nearest cent with integer math.
 */
export function sliderPositionToCents(position: number, range: SliderRange): bigint {
  const clamped = Math.min(SLIDER_MAX_POSITION, Math.max(SLIDER_MIN_POSITION, Math.round(position)));
  if (clamped <= SLIDER_CENTER_POSITION) {
    if (range.centerCents <= 0n) {
      return 0n;
    }
    return (range.centerCents * BigInt(clamped) + POSITIONS_PER_SIDE / 2n) / POSITIONS_PER_SIDE;
  }
  const span = range.upperCents - range.centerCents;
  if (span <= 0n) {
    return range.centerCents;
  }
  const offset = BigInt(clamped - SLIDER_CENTER_POSITION);
  return range.centerCents + (span * offset + POSITIONS_PER_SIDE / 2n) / POSITIONS_PER_SIDE;
}

/**
 * Inverse mapping: nearest slider position for an amount in cents.
 * Values outside the range clamp to the extremes.
 */
export function sliderCentsToPosition(cents: bigint, range: SliderRange): number {
  if (cents <= range.lowerCents) {
    return SLIDER_MIN_POSITION;
  }
  // The center check comes before the upper clamp so the reported amount
  // always renders at the physical center, even when the right side
  // collapses (reported at MAX_MONEY: center == upper).
  if (cents <= range.centerCents) {
    if (range.centerCents <= 0n) {
      return SLIDER_CENTER_POSITION;
    }
    return Number((cents * POSITIONS_PER_SIDE + range.centerCents / 2n) / range.centerCents);
  }
  if (cents >= range.upperCents) {
    return SLIDER_MAX_POSITION;
  }
  const span = range.upperCents - range.centerCents;
  if (span <= 0n) {
    return SLIDER_CENTER_POSITION;
  }
  return (
    SLIDER_CENTER_POSITION + Number(((cents - range.centerCents) * POSITIONS_PER_SIDE + span / 2n) / span)
  );
}
