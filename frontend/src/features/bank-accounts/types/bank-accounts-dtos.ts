import { z } from "zod";

import { CurrencySchema } from "@/features/payments/types/payments-dtos";

export const BankAccountDTOSchema = z.object({
  id: z.number(),
  bankName: z.string(),
  accountLabel: z.string(),
  accountHolder: z.string(),
  accountNumber: z.string(),
  taxId: z.string(),
  cbu: z.string(),
  alias: z.string(),
  currency: CurrencySchema,
  active: z.boolean(),
  displayOrder: z.number().int().nonnegative(),
});

export type BankAccountDTO = z.infer<typeof BankAccountDTOSchema>;

export const BankAccountFormDTOSchema = z.object({
  bankName: z.string().trim().min(1),
  accountLabel: z.string().trim().min(1),
  accountHolder: z.string().trim().min(1),
  accountNumber: z.string().trim().min(1),
  taxId: z.string().trim().min(1),
  cbu: z.string().trim().min(1),
  alias: z.string().trim().min(1),
  currency: CurrencySchema,
  displayOrder: z.number().int().nonnegative(),
});

export type BankAccountFormDTO = z.infer<typeof BankAccountFormDTOSchema>;
