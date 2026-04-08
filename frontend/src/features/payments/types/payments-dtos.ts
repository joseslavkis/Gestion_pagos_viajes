import { z } from "zod";

const MoneySchema = z.union([z.string(), z.number()]).transform((value) => Number(value));

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
  totalDue: MoneySchema,
  paidAmount: MoneySchema,
  remainingAmount: MoneySchema,
  reportedAmount: MoneySchema,
  amountInTripCurrency: MoneySchema,
  status: ReceiptStatusSchema.nullable(),
});
export type PaymentBatchInstallmentDTO = z.infer<typeof PaymentBatchInstallmentDTOSchema>;

export const PaymentBatchPreviewDTOSchema = z.object({
  anchorInstallmentId: z.number(),
  tripCurrency: CurrencySchema,
  paymentCurrency: CurrencySchema,
  reportedAmount: MoneySchema,
  maxAllowedAmount: MoneySchema,
  exchangeRate: MoneySchema.nullable(),
  totalPendingAmountInTripCurrency: MoneySchema,
  amountInTripCurrency: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  installments: PaymentBatchInstallmentDTOSchema.array(),
});
export type PaymentBatchPreviewDTO = z.infer<typeof PaymentBatchPreviewDTOSchema>;

export const PaymentSubmissionDTOSchema = z.object({
  submissionId: z.number(),
  status: PaymentHistoryStatusSchema,
  reportedAmount: MoneySchema,
  approvedAmount: MoneySchema,
  rejectedAmount: MoneySchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: MoneySchema.nullable(),
  amountInTripCurrency: MoneySchema,
  approvedAmountInTripCurrency: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
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
  reportedAmount: MoneySchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: MoneySchema.nullable(),
  amountInTripCurrency: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
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
  totalDue: MoneySchema,
  paidAmount: MoneySchema,
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
  reportedAmount: MoneySchema.refine((value) => value > 0, "El monto debe ser mayor a cero."),
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentCurrency: CurrencySchema,
  paymentMethod: PaymentMethodSchema,
  bankAccountId: z.number().int().positive(),
});
export type RegisterPaymentDTO = z.infer<typeof RegisterPaymentDTOSchema>;

export type RegisterPaymentFormData = {
  anchorInstallmentId: number;
  reportedAmount: number;
  reportedPaymentDate: string;
  paymentCurrency: Currency;
  paymentMethod: PaymentMethod;
  bankAccountId: number;
  file?: File | null;
};

export const PaymentPreviewRequestDTOSchema = z.object({
  anchorInstallmentId: z.number().int().positive(),
  reportedAmount: MoneySchema.refine((value) => value > 0, "El monto debe ser mayor a cero."),
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentCurrency: CurrencySchema,
});
export type PaymentPreviewRequestDTO = z.infer<typeof PaymentPreviewRequestDTOSchema>;

export const ReviewPaymentDTOSchema = z.object({
  approvedAmount: MoneySchema.refine((value) => value >= 0, "El monto aprobado no puede ser negativo."),
  adminObservation: z.string().optional(),
});
export type ReviewPaymentDTO = z.infer<typeof ReviewPaymentDTOSchema>;

export const PendingPaymentReviewDTOSchema = z.object({
  submissionId: z.number(),
  status: PaymentHistoryStatusSchema,
  reportedAmount: MoneySchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: MoneySchema.nullable(),
  amountInTripCurrency: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
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
