import { z } from "zod";

export const LoginRequestSchema = z.object({
  username: z.string().email("Debe ser un email válido (ej: nombre@gmail.com)"),
  password: z.string().min(8, "La contraseña debe tener al menos 8 caracteres"),
});

export type LoginRequest = z.infer<typeof LoginRequestSchema>;

export const LoginResponseSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().nullable(),
});

export type LoginResponse = z.infer<typeof LoginResponseSchema>;
