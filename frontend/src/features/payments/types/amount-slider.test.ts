import { describe, expect, it } from "vitest";

import {
  SLIDER_CENTER_POSITION,
  SLIDER_MAX_POSITION,
  SLIDER_MIN_POSITION,
  decimalStringToCents,
  extendSliderUpper,
  reportedSliderRange,
  sliderCentsToPosition,
  sliderPositionToCents,
} from "@/features/payments/types/amount-slider";
import { MAX_MONEY_CENTS } from "@/features/payments/types/receipt-amounts";

describe("amount-slider", () => {
  it("parses canonical decimals to cents without floats", () => {
    expect(decimalStringToCents("240.00")).toBe(24000n);
    expect(decimalStringToCents("0")).toBe(0n);
    expect(decimalStringToCents("0.01")).toBe(1n);
    expect(decimalStringToCents("99999999.99")).toBe(MAX_MONEY_CENTS);
    expect(decimalStringToCents("abc")).toBeNull();
    expect(decimalStringToCents("1.005")).toBeNull();
    expect(decimalStringToCents("-1")).toBeNull();
    expect(decimalStringToCents("250.")).toBeNull();
  });

  it("builds a convenience range with the reported amount pinned at the center", () => {
    const range = reportedSliderRange("240.00");
    expect(range).toEqual({ lowerCents: 0n, centerCents: 24000n, upperCents: 48000n });
    expect(sliderPositionToCents(SLIDER_CENTER_POSITION, range)).toBe(24000n);
    expect(sliderPositionToCents(SLIDER_MIN_POSITION, range)).toBe(0n);
    expect(sliderPositionToCents(SLIDER_MAX_POSITION, range)).toBe(48000n);
  });

  it("caps the right extreme at MAX_MONEY without moving the center", () => {
    const range = reportedSliderRange("60000000.00");
    expect(range.centerCents).toBe(6000000000n);
    expect(range.upperCents).toBe(MAX_MONEY_CENTS);
    expect(sliderPositionToCents(SLIDER_CENTER_POSITION, range)).toBe(6000000000n);
    expect(sliderPositionToCents(SLIDER_MAX_POSITION, range)).toBe(MAX_MONEY_CENTS);
  });

  it("keeps the center exact at the persistible ceiling", () => {
    const range = reportedSliderRange("99999999.99");
    expect(range.centerCents).toBe(MAX_MONEY_CENTS);
    expect(range.upperCents).toBe(MAX_MONEY_CENTS);
    expect(sliderPositionToCents(SLIDER_CENTER_POSITION, range)).toBe(MAX_MONEY_CENTS);
    expect(sliderPositionToCents(SLIDER_MAX_POSITION, range)).toBe(MAX_MONEY_CENTS);
    expect(sliderPositionToCents(SLIDER_MIN_POSITION, range)).toBe(0n);
    // With the right side collapsed, the reported amount still renders centered.
    expect(sliderCentsToPosition(MAX_MONEY_CENTS, range)).toBe(SLIDER_CENTER_POSITION);
  });

  it("maps each cent step without floats on round amounts", () => {
    const range = reportedSliderRange("240.00");
    // Right side: 48 cents per position; 6000 cents above reported is 125 steps.
    expect(sliderPositionToCents(625, range)).toBe(30000n);
    expect(sliderCentsToPosition(30000n, range)).toBe(625);
    // Left side mirrors exactly.
    expect(sliderPositionToCents(375, range)).toBe(18000n);
    expect(sliderCentsToPosition(18000n, range)).toBe(375);
  });

  it("rounds to the nearest cent on uneven spans", () => {
    const range = reportedSliderRange("100.00");
    // 10000 cents / 500 positions = 20 cents per step, exact.
    expect(sliderPositionToCents(501, range)).toBe(10020n);
    // 333.33 splits unevenly; conversion stays within half a cent per side.
    const odd = reportedSliderRange("333.33");
    const down = sliderPositionToCents(499, odd);
    expect(down).toBeLessThan(33333n);
    expect(33333n - down).toBeLessThanOrEqual(70n);
  });

  it("clamps out-of-range positions and amounts", () => {
    const range = reportedSliderRange("240.00");
    expect(sliderPositionToCents(-10, range)).toBe(0n);
    expect(sliderPositionToCents(1200, range)).toBe(48000n);
    expect(sliderCentsToPosition(-5n, range)).toBe(SLIDER_MIN_POSITION);
    expect(sliderCentsToPosition(99999999n, range)).toBe(SLIDER_MAX_POSITION);
  });

  it("extends the upper bound for manual amounts without moving the center", () => {
    const base = reportedSliderRange("240.00");
    const extended = extendSliderUpper(base, 70000n);
    expect(extended.centerCents).toBe(24000n);
    expect(extended.lowerCents).toBe(0n);
    expect(extended.upperCents).toBe(70000n);
    expect(sliderPositionToCents(SLIDER_CENTER_POSITION, extended)).toBe(24000n);
    expect(sliderPositionToCents(SLIDER_MAX_POSITION, extended)).toBe(70000n);
    expect(sliderCentsToPosition(70000n, extended)).toBe(SLIDER_MAX_POSITION);
  });

  it("never extends beyond MAX_MONEY and never shrinks", () => {
    const base = reportedSliderRange("240.00");
    expect(extendSliderUpper(base, MAX_MONEY_CENTS + 1n).upperCents).toBe(MAX_MONEY_CENTS);
    expect(extendSliderUpper(base, 10000n)).toBe(base);
    expect(extendSliderUpper(base, 48000n)).toBe(base);
  });

  it("handles a zero reported amount without crashing", () => {
    const range = reportedSliderRange("0.00");
    expect(sliderPositionToCents(SLIDER_CENTER_POSITION, range)).toBe(0n);
    expect(sliderPositionToCents(SLIDER_MIN_POSITION, range)).toBe(0n);
    expect(sliderCentsToPosition(0n, range)).toBe(SLIDER_MIN_POSITION);
  });
});
