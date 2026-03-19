import { z } from "zod";
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

export { StatusResponseDTOSchema };
export type { StatusResponseDTO };

