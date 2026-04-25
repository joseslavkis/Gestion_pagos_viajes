import { z } from "zod";
import { StatusResponseDTOSchema } from "@/lib/backend-dtos";
import type { StatusResponseDTO } from "@/lib/backend-dtos";
import { isCanonicalStudentDni, normalizeStudentDniInput } from "@/lib/dni";
import { calculateInstallmentAmountPlan } from "@/features/trips/lib/installment-amounts";

const MoneySchema = z.union([z.string(), z.number()])
  .transform((val) => {
    const n = Number(val);
    if (isNaN(n)) throw new Error(`Valor monetario inválido: ${String(val)}`);
    return n;
  })
  .refine((n) => n <= Number.MAX_SAFE_INTEGER, {
    message: "Monto excede la precisión segura de JavaScript",
  });

export const CurrencySchema = z.enum(["ARS", "USD"]);
export const InstallmentUiStatusCodeSchema = z.enum([
  "PAID",
  "UP_TO_DATE",
  "UNDER_REVIEW",
  "DUE_SOON",
  "OVERDUE",
  "RECEIPT_REJECTED",
  "RETROACTIVE_DEBT",
]);
export const InstallmentUiStatusToneSchema = z.enum(["green", "yellow", "red"]);

const FutureOrPresentDateSchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "Formato inválido, use YYYY-MM-DD");

// Backend DTOs:
// - backend/src/main/java/com/agencia/pagos/dtos/response/TripDetailDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/response/TripSummaryDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/response/BulkAssignResultDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/response/StatusResponseDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/request/TripCreateDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/request/TripUpdateDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/request/UserAssignBulkDTO.java

export const TripDetailDTOSchema = z.object({
  id: z.number(),
  name: z.string(),
  totalAmount: MoneySchema,
  firstInstallmentAmount: MoneySchema.default(0),
  currency: CurrencySchema,
  installmentsCount: z.number(),
  dueDay: z.number(),
  yellowWarningDays: z.number(),
  fixedFineAmount: MoneySchema,
  retroactiveActive: z.boolean(),
  firstDueDate: z.string(), // LocalDate serialized as "YYYY-MM-DD"
  assignedUsersCount: z.number(),
  assignedParticipantsCount: z.number(),
});

export type TripDetailDTO = z.infer<typeof TripDetailDTOSchema>;

export const TripSummaryDTOSchema = z.object({
  id: z.number(),
  name: z.string(),
  totalAmount: MoneySchema,
  firstInstallmentAmount: MoneySchema.default(0),
  currency: CurrencySchema,
  installmentsCount: z.number(),
  assignedUsersCount: z.number(),
  assignedParticipantsCount: z.number(),
});

export type TripSummaryDTO = z.infer<typeof TripSummaryDTOSchema>;

export const BulkAssignResultDTOSchema = z.object({
  status: z.string(),
  message: z.string(),
  assignedCount: z.number(),
  pendingCount: z.number(),
});

export type BulkAssignResultDTO = z.infer<typeof BulkAssignResultDTOSchema>;

export const TripStudentAdminStatusSchema = z.enum(["ASSIGNED", "PENDING"]);

export const TripStudentAdminDTOSchema = z.object({
  studentDni: z.string(),
  studentId: z.number().nullable(),
  studentName: z.string().nullable(),
  parentUserId: z.number().nullable(),
  parentFullName: z.string().nullable(),
  parentEmail: z.string().nullable(),
  status: TripStudentAdminStatusSchema,
  installmentsCount: z.number(),
});

export type TripStudentAdminDTO = z.infer<typeof TripStudentAdminDTOSchema>;

export { StatusResponseDTOSchema };
export type { StatusResponseDTO };

// Requests
export const TripCreateDTOSchema = z
  .object({
    name: z.string().min(2).max(100),
    totalAmount: z.number().positive().max(Number.MAX_SAFE_INTEGER),
    firstInstallmentAmount: z.number().positive().max(Number.MAX_SAFE_INTEGER),
    currency: CurrencySchema.default("ARS"),
    installmentsCount: z.number().int().min(1).max(60),
    dueDay: z.number().int().min(1).max(31),
    yellowWarningDays: z.number().int().min(0).max(30),
    fixedFineAmount: z.number().min(0).max(Number.MAX_SAFE_INTEGER),
    retroactiveActive: z.boolean(),
    firstDueDate: FutureOrPresentDateSchema,
  })
  .superRefine((value, ctx) => {
    try {
      calculateInstallmentAmountPlan(
        value.totalAmount,
        value.firstInstallmentAmount,
        value.installmentsCount,
      );
    } catch (error) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["firstInstallmentAmount"],
        message: error instanceof Error ? error.message : "Primera cuota inválida.",
      });
    }
  });

export type TripCreateDTO = z.input<typeof TripCreateDTOSchema>;

export const TripUpdateDTOSchema = z.object({
  name: z.string().min(2).max(100).optional(),
  dueDay: z.number().min(1).max(31).optional(),
  yellowWarningDays: z.number().min(0).max(30).optional(),
  fixedFineAmount: z.number().min(0).max(Number.MAX_SAFE_INTEGER).optional(),
  retroactiveActive: z.boolean().optional(),
  firstDueDate: FutureOrPresentDateSchema.optional(),
});

export type TripUpdateDTO = z.infer<typeof TripUpdateDTOSchema>;

export const UserAssignBulkDTOSchema = z.object({
  studentDnis: z
    .array(
      z
        .string()
        .transform(normalizeStudentDniInput)
        .refine(isCanonicalStudentDni, "DNI inválido"),
    )
    .min(1)
    .max(500)
    .refine((dnis) => new Set(dnis).size === dnis.length, {
      message: "Los DNIs no deben repetirse",
    }),
});

export type UserAssignBulkDTO = z.infer<typeof UserAssignBulkDTOSchema>;

// Schemas para el spreadsheet

export const SpreadsheetRowInstallmentDTOSchema = z.object({
  id: z.number(),
  installmentNumber: z.number(),
  dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  capitalAmount: MoneySchema,
  retroactiveAmount: MoneySchema,
  fineAmount: MoneySchema,
  totalDue: MoneySchema,
  paidAmount: MoneySchema,
  status: z.enum(["GREEN", "YELLOW", "RED", "RETROACTIVE"]),
  uiStatusCode: InstallmentUiStatusCodeSchema,
  uiStatusLabel: z.string(),
  uiStatusTone: InstallmentUiStatusToneSchema,
});

export type SpreadsheetRowInstallmentDTO = z.infer<typeof SpreadsheetRowInstallmentDTOSchema>;

export const SpreadsheetRowDTOSchema = z.object({
  userId: z.number(),
  studentId: z.number().nullable(),
  name: z.string(),
  lastname: z.string(),
  phone: z.string().nullable(),
  email: z.string(),
  studentLastname: z.string().nullable(),
  studentName: z.string().nullable(),
  studentDni: z.string().nullable(),
  userCompleted: z.boolean(),
  installments: SpreadsheetRowInstallmentDTOSchema.array(),
});

export type SpreadsheetRowDTO = z.infer<typeof SpreadsheetRowDTOSchema>;

export const SpreadsheetDTOSchema = z.object({
  tripName: z.string(),
  installmentsCount: z.number(),
  page: z.number(),
  totalElements: z.number(),
  rows: SpreadsheetRowDTOSchema.array(),
});

export type SpreadsheetDTO = z.infer<typeof SpreadsheetDTOSchema>;

// Schema y tipo para los parámetros de query
export const SpreadsheetParamsSchema = z.object({
  page: z.number().min(0).default(0),
  size: z.number().min(1).max(100).default(20),
  search: z.string().optional(),
  sortBy: z.enum(["student", "parent", "email"]).default("student"),
  order: z.enum(["asc", "desc"]).default("asc"),
  status: z.enum(["GREEN", "YELLOW", "RED", "RETROACTIVE", ""]).optional(),
});

export type SpreadsheetParams = z.infer<typeof SpreadsheetParamsSchema>;
