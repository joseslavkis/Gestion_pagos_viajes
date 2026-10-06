import { describe, expect, it } from "vitest";

import { compareNonNegativeDecimalStrings, normalizePaymentDecimalInput } from "./decimal-strings";
import {
  PaymentBatchPreviewDTOSchema,
  PaymentCalculationRequestDTOSchema,
  PaymentCalculationResponseDTOSchema,
  PaymentInstallmentHistoryDTOSchema,
  PaymentSubmissionDTOSchema,
  PendingPaymentReviewDTOSchema,
  RegisterPaymentDTOSchema,
  ReviewPaymentDTOSchema,
} from "./payments-dtos";

const installment = {
  receiptId: null,
  installmentId: 1,
  installmentNumber: 1,
  dueDate: "2026-09-18",
  totalDue: "10.16",
  paidAmount: "0.00",
  remainingAmount: "10.16",
  reportedAmount: "0.01",
  amountInTripCurrency: "10.16",
  status: null,
  allocationCurrency: "USD",
};

const noApproval = {
  approvedCurrency: null, approvedExchangeRate: null, approvedQuoteRequestedDate: null,
  approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
  approvedQuoteProviderTimestamp: null, approvedCalculationVersion: null, rejectedCurrency: null,
};

const submission = {
  submissionId: 1, status: "PENDING", reportedAmount: "306000.00", paymentCurrency: "ARS",
  exchangeRate: "1530.00000000", amountInTripCurrency: "200.00", approvedAmount: "0.00",
  approvedAmountInTripCurrency: "0.00", rejectedAmount: "0.00", reportedPaymentDate: "2026-09-03",
  paymentMethod: "CASH", fileKey: "", adminObservation: null, bankAccountId: null,
  bankAccountDisplayName: null, bankAccountAlias: null, tripId: 1, tripName: "Trip", tripCurrency: "USD",
  studentId: null, studentName: null, studentDni: null, installments: [], source: "CUSTOMER_SUBMISSION",
  ...noApproval,
};

describe("payment decimal contracts", () => {
  it("requires independent approved currency and never substitutes original FX for identity approval", () => {
    const approved = PaymentSubmissionDTOSchema.parse({
      ...submission, status: "PARTIALLY_APPROVED", approvedAmount: "150.00", approvedCurrency: "USD",
      approvedAmountInTripCurrency: "150.00", approvedCalculationVersion: "2",
      rejectedCurrency: "ARS", rejectedAmount: "76500.00",
    });
    expect(approved.exchangeRate).toBe("1530.00000000");
    expect(approved.approvedExchangeRate).toBeNull();
    expect(approved.approvedCurrency).toBe("USD");
    expect(PaymentSubmissionDTOSchema.safeParse({ ...approved, approvedCurrency: undefined }).success).toBe(false);
    expect(PaymentSubmissionDTOSchema.safeParse({ ...approved, approvedExchangeRate: 1530 }).success).toBe(false);
  });

  it("preserves a new administrative quote separately through void and authentic legacy null metadata", () => {
    const administrative = {
      ...submission, status: "VOIDED", paymentCurrency: "USD", reportedAmount: "200.00", exchangeRate: null,
      approvedCurrency: "ARS", approvedExchangeRate: "1530.00000000", approvedQuoteRequestedDate: "2026-09-03",
      approvedQuoteEffectiveDate: "2026-09-02", approvedQuoteSource: "historical", approvedQuoteProvider: "provider-b",
      approvedQuoteProviderTimestamp: "2026-09-02T12:00:00Z", approvedCalculationVersion: "2",
    };
    expect(PaymentSubmissionDTOSchema.parse(administrative)).toMatchObject(administrative);
    expect(PaymentSubmissionDTOSchema.parse({ ...submission, status: "APPROVED", approvedCurrency: "ARS" })
      .approvedQuoteProvider).toBeNull();
    expect(PaymentSubmissionDTOSchema.parse(submission).approvedCurrency).toBeNull();
  });
  it("preserves canonical decimal strings and rate scale in preview responses", () => {
    const parsed = PaymentBatchPreviewDTOSchema.parse({
      anchorInstallmentId: 1,
      tripCurrency: "ARS",
      paymentCurrency: "USD",
      reportedAmount: "0.01",
      maxAllowedAmount: "0.01",
      exchangeRate: "1015.50",
      totalPendingAmountInTripCurrency: "10.16",
      amountInTripCurrency: "10.16",
      reportedPaymentDate: "2026-09-18",
      quoteRequestedDate: "2026-09-18",
      quoteEffectiveDate: "2026-09-18",
      quoteSource: "official",
      quoteProvider: "provider-a",
      quoteProviderTimestamp: "2026-09-18T12:00:00Z",
      calculationVersion: "2",
      previewToken: "token",
      installments: [installment],
    });

    expect(parsed.exchangeRate).toBe("1015.50");
    expect(parsed.reportedAmount).toBe("0.01");
    expect(parsed.installments[0].remainingAmount).toBe("10.16");
    expect((parsed as Record<string, unknown>).quoteProvider).toBe("provider-a");
  });

  it("rejects JSON numbers in calculation response paths", () => {
    const result = PaymentBatchPreviewDTOSchema.safeParse({
      anchorInstallmentId: 1,
      tripCurrency: "ARS",
      paymentCurrency: "USD",
      reportedAmount: 0.01,
      maxAllowedAmount: "0.01",
      exchangeRate: "1234.567",
      totalPendingAmountInTripCurrency: "12.35",
      amountInTripCurrency: "12.35",
      reportedPaymentDate: "2026-09-18",
      installments: [installment],
    });

    expect(result.success).toBe(false);
  });

  it("keeps provider identity distinct on submission history", () => {
    const result = PaymentSubmissionDTOSchema.safeParse({
      ...noApproval,
      submissionId: 1,
      status: "PENDING",
      reportedAmount: "1.00",
      approvedAmount: "0.00",
      rejectedAmount: "0.00",
      paymentCurrency: "USD",
      exchangeRate: "1234.567",
      amountInTripCurrency: "1234.57",
      approvedAmountInTripCurrency: "0.00",
      reportedPaymentDate: "2026-09-18",
      quoteRequestedDate: "2026-09-18",
      quoteEffectiveDate: "2026-09-18",
      quoteSource: "official",
      quoteProvider: "provider-a",
      quoteProviderTimestamp: "2026-09-18T12:00:00Z",
      calculationVersion: "2",
      paymentMethod: "BANK_TRANSFER",
      fileKey: "receipt",
      fileKeys: ["first", "second"],
      adminObservation: null,
      bankAccountId: 1,
      bankAccountDisplayName: "Account",
      bankAccountAlias: "ACCOUNT",
      tripId: 1,
      tripName: "Trip",
      tripCurrency: "ARS",
      studentId: null,
      studentName: null,
      studentDni: null,
      installments: [installment],
      source: "CUSTOMER_SUBMISSION",
    });

    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.quoteSource).toBe("official");
      expect(result.data.quoteProvider).toBe("provider-a");
      expect(result.data.calculationVersion).toBe("2");
      expect(result.data.fileKeys).toEqual(["first", "second"]);
    }
  });

  it("preserves quote identity and decimal scale in pending review and installment history", () => {
    const quoteIdentity = {
      quoteRequestedDate: "2026-09-18",
      quoteEffectiveDate: "2026-09-17",
      quoteSource: "official",
      quoteProvider: "provider-a",
      quoteProviderTimestamp: "2026-09-17T12:00:00Z",
      calculationVersion: "2",
    };
    const pending = PendingPaymentReviewDTOSchema.parse({
      submissionId: 1,
      status: "PENDING",
      reportedAmount: "0.01",
      paymentCurrency: "USD",
      exchangeRate: "1015.50",
      amountInTripCurrency: "10.16",
      reportedPaymentDate: "2026-09-18",
      ...quoteIdentity,
      paymentMethod: "BANK_TRANSFER",
      fileKey: "receipt",
      fileKeys: ["first", "second"],
      bankAccountId: 1,
      bankAccountDisplayName: "Account",
      bankAccountAlias: "ACCOUNT",
      tripId: 1,
      tripName: "Trip",
      tripCurrency: "ARS",
      userId: 2,
      userName: "Ada",
      userLastname: "Lovelace",
      userEmail: "ada@example.com",
      studentName: null,
      studentDni: null,
      allocations: [installment],
    });
    const history = PaymentInstallmentHistoryDTOSchema.parse({
      originalReportedAmount: "0.01", allocationCurrency: "USD", allocationExchangeRate: "1234.567",
      allocationQuoteRequestedDate: "2026-09-18", allocationQuoteEffectiveDate: "2026-09-17",
      allocationQuoteSource: "official", allocationQuoteProvider: "provider-a",
      allocationQuoteProviderTimestamp: "2026-09-17T12:00:00Z", allocationCalculationVersion: "2",
      id: 1,
      submissionId: 1,
      installmentId: 1,
      installmentNumber: 1,
      reportedAmount: "0.01",
      paymentCurrency: "USD",
      exchangeRate: "1234.567",
      amountInTripCurrency: "12.35",
      reportedPaymentDate: "2026-09-18",
      ...quoteIdentity,
      paymentMethod: "BANK_TRANSFER",
      status: "APPROVED",
      fileKey: "receipt",
      fileKeys: ["first", "second"],
      adminObservation: null,
      bankAccountId: 1,
      bankAccountDisplayName: "Account",
      bankAccountAlias: "ACCOUNT",
      source: "CUSTOMER_SUBMISSION",
    });

    expect(pending.exchangeRate).toBe("1015.50");
    expect(pending.quoteProvider).toBe("provider-a");
    expect(history.exchangeRate).toBe("1234.567");
    expect(history).toMatchObject(quoteIdentity);
    expect(pending.fileKeys).toEqual(["first", "second"]);
    expect(history.fileKeys).toEqual(["first", "second"]);
  });

  it("models authoritative calculation states with explicit currencies and decimal strings", () => {
    const parsed = PaymentCalculationResponseDTOSchema.parse({
      status: "READY",
      intent: "REMAINING",
      anchorInstallmentId: 1,
      tripCurrency: "USD",
      paymentCurrency: "ARS",
      reportedAmount: "10.16",
      amountInTripCurrency: "0.01",
      anchorRemainingAmount: "0.01",
      totalPendingAmountInTripCurrency: "0.01",
      maxAllowedAmount: "15.23",
      tripCurrencyResidual: "0.00",
      exchangeRate: "1015.50",
      reportedPaymentDate: "2026-09-18",
      quoteRequestedDate: "2026-09-18",
      quoteEffectiveDate: "2026-09-18",
      quoteSource: "official",
      quoteProvider: "provider-a",
      quoteProviderTimestamp: "2026-09-18T12:00:00Z",
      calculationVersion: "2",
      previewToken: "token",
      installments: [installment],
      message: null,
    });

    expect(parsed.status).toBe("READY");
    expect(parsed.tripCurrency).toBe("USD");
    expect(parsed.paymentCurrency).toBe("ARS");
    expect(parsed.reportedAmount).toBe("10.16");
    expect(parsed.exchangeRate).toBe("1015.50");
  });

  it("accepts explicit unpayable state without inventing a calculated amount", () => {
    const parsed = PaymentCalculationResponseDTOSchema.parse({
      status: "UNPAYABLE",
      intent: "REMAINING",
      anchorInstallmentId: 1,
      tripCurrency: "ARS",
      paymentCurrency: "USD",
      reportedAmount: null,
      amountInTripCurrency: null,
      anchorRemainingAmount: "0.01",
      totalPendingAmountInTripCurrency: "0.01",
      maxAllowedAmount: "0.00",
      tripCurrencyResidual: null,
      exchangeRate: "1015.50",
      reportedPaymentDate: "2026-09-18",
      quoteRequestedDate: "2026-09-18",
      quoteEffectiveDate: "2026-09-18",
      quoteSource: "official",
      quoteProvider: "provider-a",
      quoteProviderTimestamp: null,
      calculationVersion: "2",
      previewToken: null,
      installments: [],
      message: "The balance cannot be represented in the selected currency.",
    });

    expect(parsed.status).toBe("UNPAYABLE");
    expect(parsed.reportedAmount).toBeNull();
    expect(parsed.maxAllowedAmount).toBe("0.00");
  });

  it("preserves decimal strings in calculation and payment command contracts", () => {
    const calculation = PaymentCalculationRequestDTOSchema.parse({
      anchorInstallmentId: 1,
      paymentCurrency: "USD",
      reportedPaymentDate: "2026-09-18",
      intent: "MANUAL",
      reportedAmount: "99.29",
    });
    const registration = RegisterPaymentDTOSchema.parse({
      anchorInstallmentId: 1,
      reportedAmount: "99.29",
      reportedPaymentDate: "2026-09-18",
      paymentCurrency: "USD",
      paymentMethod: "BANK_TRANSFER",
      bankAccountId: 1,
    });
    const review = ReviewPaymentDTOSchema.parse({ approvedAmount: "0.01", approvedCurrency: "USD" });

    expect(calculation.reportedAmount).toBe("99.29");
    expect(registration.reportedAmount).toBe("99.29");
    expect(review.approvedAmount).toBe("0.01");
    expect(review.approvedCurrency).toBe("USD");
  });

  it("requires an explicit supported administrative currency and trims bounded observations", () => {
    expect(ReviewPaymentDTOSchema.safeParse({ approvedAmount: "150.00" }).success).toBe(false);
    expect(ReviewPaymentDTOSchema.safeParse({ approvedAmount: "150.00", approvedCurrency: "EUR" }).success).toBe(false);
    expect(ReviewPaymentDTOSchema.parse({ approvedAmount: "150.00", approvedCurrency: "USD", adminObservation: " Confirmed credit " }))
      .toEqual({ approvedAmount: "150.00", approvedCurrency: "USD", adminObservation: "Confirmed credit" });
    expect(ReviewPaymentDTOSchema.parse({ approvedAmount: "0.00", approvedCurrency: "ARS" }).approvedAmount).toBe("0.00");
    expect(ReviewPaymentDTOSchema.parse({ approvedAmount: "150.00", approvedCurrency: "USD", adminObservation: "x".repeat(500) }).adminObservation).toHaveLength(500);
    expect(ReviewPaymentDTOSchema.safeParse({ approvedAmount: "150.00", approvedCurrency: "USD", adminObservation: "x".repeat(501) }).success).toBe(false);
  });

  it("rejects numeric and exponent notation in financial command contracts", () => {
    const numericCalculation = PaymentCalculationRequestDTOSchema.safeParse({
      anchorInstallmentId: 1,
      paymentCurrency: "USD",
      reportedPaymentDate: "2026-09-18",
      intent: "MANUAL",
      reportedAmount: 99.29,
    });
    const exponentRegistration = RegisterPaymentDTOSchema.safeParse({
      anchorInstallmentId: 1,
      reportedAmount: "1e3",
      reportedPaymentDate: "2026-09-18",
      paymentCurrency: "ARS",
      paymentMethod: "CASH",
      bankAccountId: 1,
    });

    expect(numericCalculation.success).toBe(false);
    expect(exponentRegistration.success).toBe(false);
  });

  it("normalizes editable decimal text without binary conversion", () => {
    expect(normalizePaymentDecimalInput(" 99,29 ")).toBe("99.29");
    expect(normalizePaymentDecimalInput("0.01")).toBe("0.01");
    expect(normalizePaymentDecimalInput("1e3")).toBeNull();
    expect(normalizePaymentDecimalInput("-0.01")).toBeNull();
  });

  it("compares decimal strings exactly across different scales", () => {
    expect(compareNonNegativeDecimalStrings("99.29", "99.290")).toBe(0);
    expect(compareNonNegativeDecimalStrings("0.01", "0.02")).toBe(-1);
    expect(compareNonNegativeDecimalStrings("10000000000.01", "9999999999.99")).toBe(1);
  });
});
