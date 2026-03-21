import { z } from "zod";

const MoneySchema = z.union([z.string(), z.number()]).transform((value) => Number(value));
export const CurrencySchema = z.enum(["ARS", "USD"]);

export const PaymentMethodSchema = z.enum(["BANK_TRANSFER", "CASH", "CARD", "OTHER"]);
export type PaymentMethod = z.infer<typeof PaymentMethodSchema>;

export const ReceiptStatusSchema = z.enum(["PENDING", "APPROVED", "REJECTED"]);
export type ReceiptStatus = z.infer<typeof ReceiptStatusSchema>;

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
});

export type PaymentReceiptDTO = z.infer<typeof PaymentReceiptDTOSchema>;

export const UserInstallmentDTOSchema = z.object({
  tripId: z.number(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  totalDue: MoneySchema,
  paidAmount: MoneySchema,
  tripCurrency: CurrencySchema,
  installmentStatus: z.enum(["GREEN", "YELLOW", "RED", "RETROACTIVE"]),
  latestReceiptStatus: ReceiptStatusSchema.nullable(),
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
});

export type RegisterPaymentDTO = z.infer<typeof RegisterPaymentDTOSchema>;

export type RegisterPaymentFormData = {
  installmentId: number;
  reportedAmount: number;
  reportedPaymentDate: string;
  paymentCurrency: z.infer<typeof CurrencySchema>;
  paymentMethod: PaymentMethod;
  file?: File | null;
};

export const ReviewPaymentDTOSchema = z.object({
  decision: ReceiptStatusSchema,
  adminObservation: z.string().optional(),
});

export type ReviewPaymentDTO = z.infer<typeof ReviewPaymentDTOSchema>;
