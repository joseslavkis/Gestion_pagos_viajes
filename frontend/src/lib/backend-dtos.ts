import { z } from "zod";

export const StatusResponseDTOSchema = z.object({
  status: z.string(),
  message: z.string(),
});

export type StatusResponseDTO = z.infer<typeof StatusResponseDTOSchema>;