import { describe, expect, it } from "vitest";

import {
  isValidMoneyInput,
  shouldKeepCompleteAnchorChecked,
} from "./manual-imputation-helpers";

describe("manual imputation helpers", () => {
  it("keeps quick action checked only when amount still matches exactly", () => {
    expect(shouldKeepCompleteAnchorChecked(true, "150", "150.00")).toBe(false);
    expect(shouldKeepCompleteAnchorChecked(true, "150.00", "150.00")).toBe(true);
    expect(shouldKeepCompleteAnchorChecked(true, "150.01", "150.00")).toBe(false);
    expect(shouldKeepCompleteAnchorChecked(false, "150.00", "150.00")).toBe(false);
    expect(shouldKeepCompleteAnchorChecked(true, "150.00", null)).toBe(false);
    expect(shouldKeepCompleteAnchorChecked(true, "abc", "150.00")).toBe(false);
  });

  it("validates money input without floating point", () => {
    expect(isValidMoneyInput("300.00")).toBe(true);
    expect(isValidMoneyInput("0.01")).toBe(true);
    expect(isValidMoneyInput("0")).toBe(true);
    expect(isValidMoneyInput("10.123")).toBe(false);
    expect(isValidMoneyInput("-5")).toBe(false);
    expect(isValidMoneyInput("abc")).toBe(false);
  });
});
