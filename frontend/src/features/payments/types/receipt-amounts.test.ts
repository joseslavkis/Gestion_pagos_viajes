import { describe, expect, it } from "vitest";
import { centsToMoney, moneyCents, receiptSubtotals, type ReceiptAmount } from "./receipt-amounts";

const receipt = (amount: string, currency: "ARS" | "USD" = "ARS"): ReceiptAmount => ({
  id: 1, file: new File(["x"], "receipt.pdf", { type: "application/pdf" }), currency, amount,
});

describe("receipt money", () => {
  it.each(["", "0", "0.00", "-1", "1.001", "1e3", "100000000"])("rejects %s", (value) => {
    expect(moneyCents(value)).toBeNull();
  });
  it("sums same and mixed currencies without floating point loss", () => {
    expect(receiptSubtotals([receipt("0.10"), receipt("0.20"), receipt("1,01", "USD")])).toEqual({
      ARS: "0.30", USD: "1.01",
    });
    expect(receiptSubtotals([receipt("2")])).toEqual({ ARS: "2.00", USD: "0.00" });
  });
  it("rejects aggregate overflow and file-count violations", () => {
    expect(receiptSubtotals([receipt("99999999.99"), receipt("0.01")])).toBeNull();
    expect(receiptSubtotals([])).toBeNull();
    expect(receiptSubtotals(Array.from({ length: 6 }, () => receipt("1")))).toBeNull();
    expect(centsToMoney(10000000000n)).toBeNull();
  });
});
