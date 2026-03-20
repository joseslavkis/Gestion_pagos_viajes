import { z } from "zod";
import { StatusResponseDTOSchema } from "@/lib/backend-dtos";
import type { StatusResponseDTO } from "@/lib/backend-dtos";

const MoneySchema = z.union([z.string(), z.number()])
  .transform((val) => {
    const n = Number(val);
    if (isNaN(n)) throw new Error(`Valor monetario inválido: ${String(val)}`);
    return n;
  })
  .refine((n) => n <= Number.MAX_SAFE_INTEGER, {
    message: "Monto excede la precisión segura de JavaScript",
  });

const FutureOrPresentDateSchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "Formato inválido, use YYYY-MM-DD")
  .refine((value) => {
    const candidate = new Date(`${value}T00:00:00`);
    if (Number.isNaN(candidate.getTime())) {
      return false;
    }

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    return candidate >= today;
  }, { message: "La primera fecha de vencimiento no puede ser en el pasado" });

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
  installmentsCount: z.number(),
  dueDay: z.number(),
  yellowWarningDays: z.number(),
  fixedFineAmount: MoneySchema,
  retroactiveActive: z.boolean(),
  firstDueDate: z.string(), // LocalDate serialized as "YYYY-MM-DD"
  assignedUsersCount: z.number(),
});

export type TripDetailDTO = z.infer<typeof TripDetailDTOSchema>;

export const TripSummaryDTOSchema = z.object({
  id: z.number(),
  name: z.string(),
  totalAmount: MoneySchema,
  installmentsCount: z.number(),
  assignedUsersCount: z.number(),
});

export type TripSummaryDTO = z.infer<typeof TripSummaryDTOSchema>;

export const BulkAssignResultDTOSchema = z.object({
  status: z.string(),
  message: z.string(),
  assignedCount: z.number(),
});

export type BulkAssignResultDTO = z.infer<typeof BulkAssignResultDTOSchema>;

export { StatusResponseDTOSchema };
export type { StatusResponseDTO };

// Requests
export const TripCreateDTOSchema = z.object({
  name: z.string().min(2).max(100),
  totalAmount: z.number().positive().max(Number.MAX_SAFE_INTEGER),
  installmentsCount: z.number().min(1).max(60),
  dueDay: z.number().min(1).max(31),
  yellowWarningDays: z.number().min(0).max(30),
  fixedFineAmount: z.number().min(0).max(Number.MAX_SAFE_INTEGER),
  retroactiveActive: z.boolean(),
  firstDueDate: FutureOrPresentDateSchema,
});

export type TripCreateDTO = z.infer<typeof TripCreateDTOSchema>;

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
  userIds: z
    .array(z.number().int().nonnegative())
    .max(500)
    .refine((ids) => new Set(ids).size === ids.length, {
      message: "Los IDs de usuario no deben repetirse",
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
  status: z.enum(["GREEN", "YELLOW", "RED", "RETROACTIVE"]),
});

export type SpreadsheetRowInstallmentDTO = z.infer<typeof SpreadsheetRowInstallmentDTOSchema>;

export const SpreadsheetRowDTOSchema = z.object({
  userId: z.number(),
  name: z.string(),
  lastname: z.string(),
  phone: z.string().nullable(),
  email: z.string(),
  studentName: z.string().nullable(),
  schoolName: z.string().nullable(),
  courseName: z.string().nullable(),
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
  sortBy: z.enum(["lastname", "name", "email"]).default("lastname"),
  order: z.enum(["asc", "desc"]).default("asc"),
  status: z.enum(["GREEN", "YELLOW", "RED", "RETROACTIVE", ""]).optional(),
});

export type SpreadsheetParams = z.infer<typeof SpreadsheetParamsSchema>;


