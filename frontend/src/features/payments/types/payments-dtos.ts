import { z } from "zod";

const MoneySchema = z.union([z.string(), z.number()]).transform((value) => Number(value));
export const CurrencySchema = z.enum(["ARS", "USD"]);
export type Currency = z.infer<typeof CurrencySchema>;

export const PaymentMethodSchema = z.enum(["BANK_TRANSFER", "CASH", "CARD", "OTHER"]);
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

export const UserInstallmentDTOSchema = z.object({
  tripId: z.number(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  totalDue: MoneySchema,
  paidAmount: MoneySchema,
  yellowWarningDays: z.number().int().nonnegative(),
  tripCurrency: CurrencySchema,
  uiStatusCode: InstallmentUiStatusCodeSchema,
  uiStatusLabel: z.string(),
  uiStatusTone: InstallmentUiStatusToneSchema,
  latestReceiptObservation: z.string().nullable(),
  userCompletedTrip: z.boolean(),
});

export type UserInstallmentDTO = z.infer<typeof UserInstallmentDTOSchema>;

export const RegisterPaymentDTOSchema = z.object({
  installmentId: z.number().int().positive(),
  reportedAmount: z.number().positive(),
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentCurrency: CurrencySchema,
  paymentMethod: PaymentMethodSchema,
  bankAccountId: z.number().int().positive(),
});

export type RegisterPaymentDTO = z.infer<typeof RegisterPaymentDTOSchema>;

export type RegisterPaymentFormData = {
  installmentId: number;
  reportedAmount: number;
  reportedPaymentDate: string;
  paymentCurrency: z.infer<typeof CurrencySchema>;
  paymentMethod: PaymentMethod;
  bankAccountId: number;
  file?: File | null;
};

export const ReviewPaymentDTOSchema = z.object({
  decision: ReceiptStatusSchema,
  adminObservation: z.string().optional(),
});

export type ReviewPaymentDTO = z.infer<typeof ReviewPaymentDTOSchema>;

export const PendingPaymentReviewDTOSchema = z.object({
  receiptId: z.number(),
  status: ReceiptStatusSchema,
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
  installmentId: z.number(),
  installmentNumber: z.number(),
  installmentDueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  installmentTotalDue: MoneySchema,
  tripId: z.number(),
  tripName: z.string(),
  tripCurrency: CurrencySchema,
  userId: z.number(),
  userName: z.string(),
  userLastname: z.string(),
  userEmail: z.string(),
  studentName: z.string().nullable(),
});

export type PendingPaymentReviewDTO = z.infer<typeof PendingPaymentReviewDTOSchema>;
