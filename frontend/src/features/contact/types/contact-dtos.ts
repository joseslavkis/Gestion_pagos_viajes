import { z } from "zod";

export const ContactMessageDTOSchema = z.object({
  name: z.string().min(1, "El nombre es requerido"),
  email: z.string().email("Debe ser un email válido"),
  message: z.string().min(1, "El mensaje es requerido"),
});

export type ContactMessageDTO = z.infer<typeof ContactMessageDTOSchema>;

export const ContactSendResponseSchema = z.object({
  status: z.string(),
  message: z.string(),
});

export type ContactSendResponse = z.infer<typeof ContactSendResponseSchema>;

