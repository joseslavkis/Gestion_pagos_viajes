import { z } from "zod";

const MoneySchema = z.union([z.string(), z.number()]).transform((value) => Number(value));

export const PaymentMethodSchema = z.enum(["BANK_TRANSFER", "CASH", "CARD", "OTHER"]);
export type PaymentMethod = z.infer<typeof PaymentMethodSchema>;

export const ReceiptStatusSchema = z.enum(["PENDING", "APPROVED", "REJECTED"]);
export type ReceiptStatus = z.infer<typeof ReceiptStatusSchema>;

export const PaymentReceiptDTOSchema = z.object({
  id: z.number(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  reportedAmount: MoneySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentMethod: PaymentMethodSchema,
  status: ReceiptStatusSchema,
  adminObservation: z.string().nullable(),
});

export type PaymentReceiptDTO = z.infer<typeof PaymentReceiptDTOSchema>;

export const RegisterPaymentDTOSchema = z.object({
  installmentId: z.number().int().positive(),
  reportedAmount: z.number().positive(),
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  paymentMethod: PaymentMethodSchema,
});

export type RegisterPaymentDTO = z.infer<typeof RegisterPaymentDTOSchema>;

export const ReviewPaymentDTOSchema = z.object({
  decision: ReceiptStatusSchema,
  adminObservation: z.string().optional(),
});

export type ReviewPaymentDTO = z.infer<typeof ReviewPaymentDTOSchema>;
