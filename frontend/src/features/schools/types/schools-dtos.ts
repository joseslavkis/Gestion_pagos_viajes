import { z } from "zod";

export const SchoolDTOSchema = z.object({
  id: z.number(),
  name: z.string(),
});

export const SchoolDTOArraySchema = SchoolDTOSchema.array();

export type SchoolDTO = z.infer<typeof SchoolDTOSchema>;

export const SchoolCreateDTOSchema = z.object({
  name: z.string().trim().min(2),
});

export type SchoolCreateDTO = z.infer<typeof SchoolCreateDTOSchema>;
