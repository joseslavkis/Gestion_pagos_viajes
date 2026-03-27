import { z } from "zod";
import {
  CurrencySchema,
  InstallmentUiStatusCodeSchema,
  InstallmentUiStatusToneSchema,
  PaymentReceiptDTOSchema,
  ReceiptStatusSchema,
} from "@/features/payments/types/payments-dtos";
import { StatusResponseDTOSchema } from "@/lib/backend-dtos";
import type { StatusResponseDTO } from "@/lib/backend-dtos";

// Backend DTOs:
// - backend/src/main/java/com/agencia/pagos/dtos/response/UserProfileDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/response/StatusResponseDTO.java

export const RoleDTOSchema = z.enum(["ADMIN", "USER"]);
export type RoleDTO = z.infer<typeof RoleDTOSchema>;

export const UserProfileDTOSchema = z.object({
  id: z.number(),
  email: z.string(),
  name: z.string(),
  lastname: z.string(),
  role: RoleDTOSchema,
});

export type UserProfileDTO = z.infer<typeof UserProfileDTOSchema>;

export const StudentDTOSchema = z.object({
  id: z.number(),
  name: z.string(),
  dni: z.string(),
  schoolName: z.string().nullable(),
  courseName: z.string().nullable(),
});

export type StudentDTO = z.infer<typeof StudentDTOSchema>;

export const AdminUserSearchResultDTOSchema = z.object({
  id: z.number(),
  email: z.string(),
  name: z.string(),
  lastname: z.string(),
  dni: z.string().nullable(),
  phone: z.string().nullable(),
  role: RoleDTOSchema,
  studentsCount: z.number().int().nonnegative(),
});

export type AdminUserSearchResultDTO = z.infer<typeof AdminUserSearchResultDTOSchema>;

export const AdminUserInstallmentDTOSchema = z.object({
  tripId: z.number(),
  tripName: z.string(),
  tripCurrency: CurrencySchema,
  studentId: z.number().nullable(),
  studentName: z.string().nullable(),
  studentDni: z.string().nullable(),
  schoolName: z.string().nullable(),
  courseName: z.string().nullable(),
  installmentId: z.number(),
  installmentNumber: z.number(),
  dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  totalDue: z.union([z.string(), z.number()]).transform((value) => Number(value)),
  paidAmount: z.union([z.string(), z.number()]).transform((value) => Number(value)),
  installmentStatus: z.enum(["GREEN", "YELLOW", "RED", "RETROACTIVE"]),
  latestReceiptStatus: ReceiptStatusSchema.nullable(),
  uiStatusCode: InstallmentUiStatusCodeSchema,
  uiStatusLabel: z.string(),
  uiStatusTone: InstallmentUiStatusToneSchema,
  latestReceiptObservation: z.string().nullable(),
});

export type AdminUserInstallmentDTO = z.infer<typeof AdminUserInstallmentDTOSchema>;

export const AdminUserDetailDTOSchema = z.object({
  id: z.number(),
  email: z.string(),
  name: z.string(),
  lastname: z.string(),
  dni: z.string().nullable(),
  phone: z.string().nullable(),
  role: RoleDTOSchema,
  students: StudentDTOSchema.array(),
  installments: AdminUserInstallmentDTOSchema.array(),
  receipts: PaymentReceiptDTOSchema.array(),
});

export type AdminUserDetailDTO = z.infer<typeof AdminUserDetailDTOSchema>;

export { StatusResponseDTOSchema };
export type { StatusResponseDTO };
