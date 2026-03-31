import { z } from "zod";

const MoneySchema = z.union([z.string(), z.number()]).transform((value) => Number(value));
export const CurrencySchema = z.enum(["ARS", "USD"]);
export type Currency = z.infer<typeof CurrencySchema>;

export const PaymentMethodSchema = z.enum(["BANK_TRANSFER", "CASH", "DEPOSIT", "OTHER"]);
export type PaymentMethod = z.infer<typeof PaymentMethodSchema>;

export const ReceiptStatusSchema = z.enum(["PENDING", "APPROVED", "REJECTED"]);
export type ReceiptStatus = z.infer<typeof ReceiptStatusSchema>;

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

export const PaymentReceiptDTOSchema = z.object({
  id: z.number(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  reportedAmount: MoneySchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: MoneySchema.nullable(),
  amountInTripCurrency: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentMethod: PaymentMethodSchema,
  status: ReceiptStatusSchema,
  fileKey: z.string(),
  adminObservation: z.string().nullable(),
  bankAccountId: z.number().nullable(),
  bankAccountDisplayName: z.string().nullable(),
  bankAccountAlias: z.string().nullable(),
});

export type PaymentReceiptDTO = z.infer<typeof PaymentReceiptDTOSchema>;

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
  installmentsCount: z.number().int().positive(),
  tripCurrency: CurrencySchema,
  paymentCurrency: CurrencySchema,
  totalReportedAmount: MoneySchema,
  exchangeRate: MoneySchema.nullable(),
  totalAmountInTripCurrency: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  installments: PaymentBatchInstallmentDTOSchema.array(),
});

export type PaymentBatchPreviewDTO = z.infer<typeof PaymentBatchPreviewDTOSchema>;

export const PaymentBatchDTOSchema = z.object({
  batchId: z.number(),
  reportedAmount: MoneySchema,
  paymentCurrency: CurrencySchema,
  exchangeRate: MoneySchema.nullable(),
  amountInTripCurrency: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentMethod: PaymentMethodSchema,
  bankAccountId: z.number().nullable(),
  bankAccountDisplayName: z.string().nullable(),
  bankAccountAlias: z.string().nullable(),
  installments: PaymentBatchInstallmentDTOSchema.array(),
});

export type PaymentBatchDTO = z.infer<typeof PaymentBatchDTOSchema>;

export const UserInstallmentDTOSchema = z.object({
  tripId: z.number(),
  studentId: z.number().nullable(),
  studentName: z.string().nullable(),
  studentDni: z.string().nullable(),
  schoolName: z.string().nullable(),
  courseName: z.string().nullable(),
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
  installmentsCount: z.number().int().positive(),
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentCurrency: CurrencySchema,
  paymentMethod: PaymentMethodSchema,
  bankAccountId: z.number().int().positive(),
});

export type RegisterPaymentDTO = z.infer<typeof RegisterPaymentDTOSchema>;

export type RegisterPaymentFormData = {
  anchorInstallmentId: number;
  installmentsCount: number;
  reportedPaymentDate: string;
  paymentCurrency: z.infer<typeof CurrencySchema>;
  paymentMethod: PaymentMethod;
  bankAccountId: number;
  file?: File | null;
};

export const PaymentPreviewRequestDTOSchema = z.object({
  anchorInstallmentId: z.number().int().positive(),
  installmentsCount: z.number().int().positive(),
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentCurrency: CurrencySchema,
});

export type PaymentPreviewRequestDTO = z.infer<typeof PaymentPreviewRequestDTOSchema>;

export const ReviewPaymentDTOSchema = z.object({
  decision: ReceiptStatusSchema,
  adminObservation: z.string().optional(),
});

export type ReviewPaymentDTO = z.infer<typeof ReviewPaymentDTOSchema>;

export const PendingPaymentReviewLineDTOSchema = z.object({
  receiptId: z.number(),
  status: ReceiptStatusSchema,
  reportedAmount: MoneySchema,
  amountInTripCurrency: MoneySchema,
  installmentId: z.number(),
  installmentNumber: z.number(),
  installmentDueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  installmentTotalDue: MoneySchema,
  adminObservation: z.string().nullable(),
});

export type PendingPaymentReviewLineDTO = z.infer<typeof PendingPaymentReviewLineDTOSchema>;

export const PendingPaymentReviewDTOSchema = z.object({
  batchId: z.number().nullable(),
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
  receipts: PendingPaymentReviewLineDTOSchema.array(),
});

export type PendingPaymentReviewDTO = z.infer<typeof PendingPaymentReviewDTOSchema>;
