import { z } from "zod";

import {
  CurrencySchema,
  DecimalStringSchema,
  PaymentSubmissionDTOSchema,
} from "./payments-dtos";

export const ManualImputationContextDTOSchema = z.object({
  eligible: z.boolean(),
  selectedInstallmentId: z.number(),
  firstPayableInstallmentId: z.number().nullable(),
  firstPayableInstallmentNumber: z.number().nullable(),
  anchorRemainingAmount: DecimalStringSchema.nullable(),
  totalRemainingAmountInTripCurrency: DecimalStringSchema.nullable(),
  tripCurrency: CurrencySchema,
  hasPendingReview: z.boolean(),
  message: z.string().nullable(),
});
export type ManualImputationContextDTO = z.infer<typeof ManualImputationContextDTOSchema>;

export const ManualImputationRequestDTOSchema = z.object({
  anchorInstallmentId: z.number().int().positive(),
  reportedAmount: DecimalStringSchema,
  paymentCurrency: CurrencySchema,
  reportedPaymentDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  previewToken: z.string().min(1),
  reason: z.string().max(500).optional(),
});
export type ManualImputationRequestDTO = z.infer<typeof ManualImputationRequestDTOSchema>;

export const ManualImputationResponseDTOSchema = PaymentSubmissionDTOSchema;
export type ManualImputationResponseDTO = z.infer<typeof ManualImputationResponseDTOSchema>;
