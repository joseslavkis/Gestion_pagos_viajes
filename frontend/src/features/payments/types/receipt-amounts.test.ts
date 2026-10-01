import { describe, expect, it } from "vitest";
import {
  centsToMoney,
  exceedsMaxMoney,
  hasMixedReceiptCurrencies,
  moneyCents,
  receiptSingleCurrencyTotal,
  type ReceiptAmount,
} from "./receipt-amounts";

const receipt = (amount: string, currency: "ARS" | "USD" = "ARS", id = 1): ReceiptAmount => ({
  id, file: new File(["x"], "receipt.pdf", { type: "application/pdf" }), currency, amount,
});

describe("receipt money", () => {
  it.each(["", "0", "0.00", "-1", "1.001", "1e3", "100000000"])("rejects %s", (value) => {
    expect(moneyCents(value)).toBeNull();
  });
  it("keeps exact cent arithmetic without floating point loss", () => {
    // 0.10 + 0.20 is 0.30 in exact cents, not 0.30000000000000004.
    expect(centsToMoney(moneyCents("0.10")! + moneyCents("0.20")!)).toBe("0.30");
    expect(moneyCents("1,01")).toBe(101n);
  });
  it("enforces the persistible money ceiling in both directions", () => {
    expect(exceedsMaxMoney("99999999.99")).toBe(false);
    expect(exceedsMaxMoney("100000000")).toBe(true);
    expect(moneyCents("100000000")).toBeNull();
    expect(centsToMoney(10000000000n)).toBeNull();
  });
});

describe("single-currency submission total", () => {
  it("sums multiple ARS receipts in their own currency", () => {
    expect(
      receiptSingleCurrencyTotal([receipt("100000", "ARS", 1), receipt("140000", "ARS", 2), receipt("50000", "ARS", 3)]),
    ).toEqual({ currency: "ARS", total: "290000.00" });
  });
  it("sums multiple USD receipts in their own currency", () => {
    expect(
      receiptSingleCurrencyTotal([receipt("100", "USD", 1), receipt("50", "USD", 2), receipt("25", "USD", 3)]),
    ).toEqual({ currency: "USD", total: "175.00" });
  });
  it("rejects mixed currencies without producing an artificial converted total", () => {
    const mixed = [receipt("100000", "ARS", 1), receipt("50", "USD", 2)];
    expect(receiptSingleCurrencyTotal(mixed)).toBeNull();
    expect(hasMixedReceiptCurrencies(mixed)).toBe(true);
  });
  it("does not flag a uniform set as mixed", () => {
    expect(hasMixedReceiptCurrencies([receipt("10", "ARS", 1), receipt("20", "ARS", 2)])).toBe(false);
    expect(hasMixedReceiptCurrencies([receipt("10", "USD", 1)])).toBe(false);
    expect(hasMixedReceiptCurrencies([])).toBe(false);
  });
  it("rejects invalid amounts, overflow and file-count violations", () => {
    expect(receiptSingleCurrencyTotal([])).toBeNull();
    expect(receiptSingleCurrencyTotal(Array.from({ length: 6 }, (_, index) => receipt("1", "ARS", index)))).toBeNull();
    expect(receiptSingleCurrencyTotal([receipt("99999999.99", "ARS", 1), receipt("0.01", "ARS", 2)])).toBeNull();
    expect(receiptSingleCurrencyTotal([receipt("", "ARS", 1)])).toBeNull();
  });
});
