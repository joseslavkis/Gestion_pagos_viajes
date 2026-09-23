import { z } from "zod";

const canonicalDecimalPattern = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/;

export const DecimalStringSchema = z.string().regex(
  canonicalDecimalPattern,
  "El valor debe ser un decimal canónico.",
);
export type DecimalString = z.infer<typeof DecimalStringSchema>;

const NonNegativeDecimalStringSchema = DecimalStringSchema.refine(
  (value) => !value.startsWith("-"),
  "El monto no puede ser negativo.",
);
const PositiveDecimalStringSchema = NonNegativeDecimalStringSchema.refine(
  (value) => /[1-9]/.test(value),
  "El monto debe ser mayor a cero.",
);

// Legacy installment due/paid summaries remain JSON numbers for display-only uses.
// Payment calculation context uses the canonical backend-computed remaining balance.
const InstallmentDisplayMoneySchema = z.number().finite();

export const CurrencySchema = z.enum(["ARS", "USD"]);
export type Currency = z.infer<typeof CurrencySchema>;

export const PaymentMethodSchema = z.enum(["BANK_TRANSFER", "CASH", "DEPOSIT", "OTHER"]);
export type PaymentMethod = z.infer<typeof PaymentMethodSchema>;

export const ReceiptStatusSchema = z.enum(["PENDING", "APPROVED", "REJECTED"]);
export type ReceiptStatus = z.infer<typeof ReceiptStatusSchema>;

export const PaymentHistoryStatusSchema = z.enum([
  "PENDING",
  "APPROVED",
  "REJECTED",
  "PARTIALLY_APPROVED",
  "VOIDED",
]);
export type PaymentHistoryStatus = z.infer<typeof PaymentHistoryStatusSchema>;

export const InstallmentUiStatusCodeSchema = z.enum([
  "PAID",
  "UP_TO_DATE",
  "UNDER_REVIEW",
  "DUE_SOON",
  "OVERDUE",
  "RECEIPT_REJECTED",
  "RETROACTIVE_DEBT",
]);
export type InstallmentUiStatusCode = z.infer<typeof InstallmentUiStatusCodeSchema>;

export const InstallmentUiStatusToneSchema = z.enum(["green", "yellow", "red"]);
export type InstallmentUiStatusTone = z.infer<typeof InstallmentUiStatusToneSchema>;

export const PaymentBatchInstallmentDTOSchema = z.object({
  receiptId: z.number().nullable(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  totalDue: DecimalStringSchema,
  paidAmount: DecimalStringSchema,
  remainingAmount: DecimalStringSchema,
  reportedAmount: DecimalStringSchema,
  amountInTripCurrency: DecimalStringSchema,
  status: ReceiptStatusSchema.nullable(),
});
export type PaymentBatchInstallmentDTO = z.infer<typeof PaymentBatchInstallmentDTOSchema>;

export const PaymentBatchPreviewDTOSchema = z.object({
  anchorInstallmentId: z.number(),
  tripCurrency: CurrencySchema,
  paymentCurrency: CurrencySchema,
  reportedAmount: DecimalStringSchema,
  maxAllowedAmount: DecimalStringSchema,
  exchangeRate: DecimalStringSchema.nullable(),
  totalPendingAmountInTripCurrency: DecimalStringSchema,
  amountInTripCurrency: DecimalStringSchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  quoteRequestedDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteEffectiveDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteSource: z.string().nullish(),
  quoteProvider: z.string().nullish(),
  quoteProviderTimestamp: z.string().nullish(),
  calculationVersion: z.string().nullish(),
  previewToken: z.string().nullish(),
  installments: PaymentBatchInstallmentDTOSchema.array(),
});
export type PaymentBatchPreviewDTO = z.infer<typeof PaymentBatchPreviewDTOSchema>;

export const PaymentSubmissionDTOSchema = z.object({
  submissionId: z.number(),
  status: PaymentHistoryStatusSchema,
  reportedAmount: DecimalStringSchema,
  approvedAmount: DecimalStringSchema,
  rejectedAmount: DecimalStringSchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: DecimalStringSchema.nullable(),
  amountInTripCurrency: DecimalStringSchema,
  approvedAmountInTripCurrency: DecimalStringSchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  quoteRequestedDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteEffectiveDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteSource: z.string().nullish(),
  quoteProvider: z.string().nullish(),
  quoteProviderTimestamp: z.string().nullish(),
  calculationVersion: z.string().nullish(),
  paymentMethod: PaymentMethodSchema,
  fileKey: z.string(),
  adminObservation: z.string().nullable(),
  bankAccountId: z.number().nullable(),
  bankAccountDisplayName: z.string().nullable(),
  bankAccountAlias: z.string().nullable(),
  tripId: z.number(),
  tripName: z.string(),
  tripCurrency: CurrencySchema,
  studentId: z.number().nullable(),
  studentName: z.string().nullable(),
  studentDni: z.string().nullable(),
  installments: PaymentBatchInstallmentDTOSchema.array(),
});
export type PaymentSubmissionDTO = z.infer<typeof PaymentSubmissionDTOSchema>;

// Backward-compatible aliases while the UI finishes migrating.
export const PaymentBatchDTOSchema = PaymentSubmissionDTOSchema;
export type PaymentBatchDTO = PaymentSubmissionDTO;

export const PaymentInstallmentHistoryDTOSchema = z.object({
  id: z.number(),
  submissionId: z.number().nullable(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  reportedAmount: DecimalStringSchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: DecimalStringSchema.nullable(),
  amountInTripCurrency: DecimalStringSchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  quoteRequestedDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteEffectiveDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteSource: z.string().nullish(),
  quoteProvider: z.string().nullish(),
  quoteProviderTimestamp: z.string().nullish(),
  calculationVersion: z.string().nullish(),
  paymentMethod: PaymentMethodSchema,
  status: PaymentHistoryStatusSchema,
  fileKey: z.string(),
  adminObservation: z.string().nullable(),
  bankAccountId: z.number().nullable(),
  bankAccountDisplayName: z.string().nullable(),
  bankAccountAlias: z.string().nullable(),
});
export type PaymentInstallmentHistoryDTO = z.infer<typeof PaymentInstallmentHistoryDTOSchema>;

export const PaymentReceiptDTOSchema = PaymentInstallmentHistoryDTOSchema;
export type PaymentReceiptDTO = PaymentInstallmentHistoryDTO;

export const UserInstallmentDTOSchema = z.object({
  tripId: z.number(),
  tripName: z.string(),
  studentId: z.number().nullable(),
  studentName: z.string().nullable(),
  studentDni: z.string().nullable(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  totalDue: InstallmentDisplayMoneySchema,
  paidAmount: InstallmentDisplayMoneySchema,
  remainingAmount: DecimalStringSchema,
  yellowWarningDays: z.number().int().nonnegative(),
  tripCurrency: CurrencySchema,
  installmentStatus: z.enum(["GREEN", "YELLOW", "RED", "RETROACTIVE"]),
  latestReceiptStatus: ReceiptStatusSchema.nullable(),
  uiStatusCode: InstallmentUiStatusCodeSchema,
  uiStatusLabel: z.string(),
  uiStatusTone: InstallmentUiStatusToneSchema,
  latestReceiptObservation: z.string().nullable(),
  userCompletedTrip: z.boolean(),
});
export type UserInstallmentDTO = z.infer<typeof UserInstallmentDTOSchema>;

export const RegisterPaymentDTOSchema = z.object({
  anchorInstallmentId: z.number().int().positive(),
  reportedAmount: PositiveDecimalStringSchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentCurrency: CurrencySchema,
  paymentMethod: PaymentMethodSchema,
  bankAccountId: z.number().int().positive(),
});
export type RegisterPaymentDTO = z.infer<typeof RegisterPaymentDTOSchema>;

export type RegisterPaymentFormData = {
  anchorInstallmentId: number;
  reportedAmount: DecimalString;
  reportedPaymentDate: string;
  paymentCurrency: Currency;
  paymentMethod: PaymentMethod;
  bankAccountId: number;
  file?: File | null;
  previewToken?: string | null;
};

export const PaymentPreviewRequestDTOSchema = z.object({
  anchorInstallmentId: z.number().int().positive(),
  reportedAmount: PositiveDecimalStringSchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentCurrency: CurrencySchema,
});
export type PaymentPreviewRequestDTO = z.infer<typeof PaymentPreviewRequestDTOSchema>;

export const ReviewPaymentDTOSchema = z.object({
  approvedAmount: NonNegativeDecimalStringSchema,
  adminObservation: z.string().optional(),
});
export type ReviewPaymentDTO = z.infer<typeof ReviewPaymentDTOSchema>;

export const PendingPaymentReviewDTOSchema = z.object({
  submissionId: z.number(),
  status: PaymentHistoryStatusSchema,
  reportedAmount: DecimalStringSchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: DecimalStringSchema.nullable(),
  amountInTripCurrency: DecimalStringSchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  quoteRequestedDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteEffectiveDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteSource: z.string().nullish(),
  quoteProvider: z.string().nullish(),
  quoteProviderTimestamp: z.string().nullish(),
  calculationVersion: z.string().nullish(),
  paymentMethod: PaymentMethodSchema,
  fileKey: z.string(),
  bankAccountId: z.number().nullable(),
  bankAccountDisplayName: z.string().nullable(),
  bankAccountAlias: z.string().nullable(),
  tripId: z.number(),
  tripName: z.string(),
  tripCurrency: CurrencySchema,
  userId: z.number(),
  userName: z.string(),
  userLastname: z.string(),
  userEmail: z.string(),
  studentName: z.string().nullable(),
  studentDni: z.string().nullable(),
  allocations: PaymentBatchInstallmentDTOSchema.array(),
});
export type PendingPaymentReviewDTO = z.infer<typeof PendingPaymentReviewDTOSchema>;

export const PaymentCalculationIntentSchema = z.enum(["REMAINING", "MANUAL"]);
export type PaymentCalculationIntent = z.infer<typeof PaymentCalculationIntentSchema>;

export const PaymentCalculationStatusSchema = z.enum([
  "READY",
  "AMOUNT_EXCEEDS_BALANCE",
  "UNPAYABLE",
  "QUOTE_UNAVAILABLE",
  "EXPIRED",
]);
export type PaymentCalculationStatus = z.infer<typeof PaymentCalculationStatusSchema>;

const PaymentCalculationRequestBaseSchema = z.object({
  anchorInstallmentId: z.number().int().positive(),
  paymentCurrency: CurrencySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  previewToken: z.string().nullish(),
});

export const PaymentCalculationRequestDTOSchema = z.discriminatedUnion("intent", [
  PaymentCalculationRequestBaseSchema.extend({
    intent: z.literal("REMAINING"),
    reportedAmount: z.undefined().optional(),
  }),
  PaymentCalculationRequestBaseSchema.extend({
    intent: z.literal("MANUAL"),
    reportedAmount: PositiveDecimalStringSchema,
  }),
]);
export type PaymentCalculationRequestDTO = z.infer<typeof PaymentCalculationRequestDTOSchema>;

export const PaymentCalculationResponseDTOSchema = z.object({
  status: PaymentCalculationStatusSchema,
  intent: PaymentCalculationIntentSchema,
  anchorInstallmentId: z.number().int().positive(),
  tripCurrency: CurrencySchema,
  paymentCurrency: CurrencySchema,
  reportedAmount: DecimalStringSchema.nullable(),
  amountInTripCurrency: DecimalStringSchema.nullable(),
  anchorRemainingAmount: DecimalStringSchema,
  totalPendingAmountInTripCurrency: DecimalStringSchema,
  maxAllowedAmount: DecimalStringSchema.nullable(),
  tripCurrencyResidual: DecimalStringSchema.nullable(),
  exchangeRate: DecimalStringSchema.nullable(),
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  quoteRequestedDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteEffectiveDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  quoteSource: z.string().nullish(),
  quoteProvider: z.string().nullish(),
  quoteProviderTimestamp: z.string().nullish(),
  calculationVersion: z.string().nullish(),
  previewToken: z.string().nullish(),
  installments: PaymentBatchInstallmentDTOSchema.array(),
  message: z.string().nullish(),
});
export type PaymentCalculationResponseDTO = z.infer<typeof PaymentCalculationResponseDTOSchema>;
