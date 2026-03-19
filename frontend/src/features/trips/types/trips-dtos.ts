import { z } from "zod";
import { StatusResponseDTOSchema } from "@/lib/backend-dtos";
import type { StatusResponseDTO } from "@/lib/backend-dtos";

// Backend DTOs:
// - backend/src/main/java/com/agencia/pagos/dtos/response/TripDetailDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/response/TripSummaryDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/response/BulkAssignResultDTO.java
// - backend/src/main/java/com/agencia/pagos/dtos/response/StatusResponseDTO.java

export const TripDetailDTOSchema = z.object({
  id: z.number(),
  name: z.string(),
  totalAmount: z.number(),
  installmentsCount: z.number(),
  dueDay: z.number(),
  yellowWarningDays: z.number(),
  fixedFineAmount: z.number(),
  retroactiveActive: z.boolean(),
  firstDueDate: z.string(), // LocalDate serialized as "YYYY-MM-DD"
  assignedUsersCount: z.number(),
});

export type TripDetailDTO = z.infer<typeof TripDetailDTOSchema>;

export const TripSummaryDTOSchema = z.object({
  id: z.number(),
  name: z.string(),
  totalAmount: z.number(),
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

// Requests (for later use in trips feature)
export const TripCreateDTOSchema = z.object({
  name: z.string().min(2).max(100),
  totalAmount: z.number().positive(),
  installmentsCount: z.number().min(1).max(60),
  dueDay: z.number().min(1).max(31),
  yellowWarningDays: z.number().min(0).max(30),
  fixedFineAmount: z.number().min(0),
  retroactiveActive: z.boolean(),
  firstDueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
});

export type TripCreateDTO = z.infer<typeof TripCreateDTOSchema>;

export const TripUpdateDTOSchema = z.object({
  name: z.string().min(2).max(100).optional(),
  dueDay: z.number().min(1).max(31).optional(),
  yellowWarningDays: z.number().min(0).max(30).optional(),
  fixedFineAmount: z.number().min(0).optional(),
  retroactiveActive: z.boolean().optional(),
  firstDueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
});

export type TripUpdateDTO = z.infer<typeof TripUpdateDTOSchema>;

