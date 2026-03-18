import { z } from "zod";

export const LoginRequestSchema = z.object({
  username: z.string().email("Debe ser un email válido (ej: nombre@gmail.com)"),
  password: z.string().min(8, "La contraseña debe tener al menos 8 caracteres"),
});

export type LoginRequest = z.infer<typeof LoginRequestSchema>;

export const SignupRequestSchema = z.object({
  username: z
    .string()
    .min(3, "El username debe tener al menos 3 caracteres")
    .regex(/^[a-zA-Z0-9._-]+$/, "El username solo puede contener letras, números, punto, guion y guion bajo"),
  password: z
    .string()
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&]).{8,}$/,
      "La contraseña debe incluir mayúscula, minúscula, número y símbolo (@$!%*?&)"
    ),
  email: z.string().email("Debe ser un email válido"),
  firstName: z.string().min(2, "El nombre debe tener al menos 2 caracteres"),
  lastName: z.string().min(2, "El apellido debe tener al menos 2 caracteres"),
  dni: z
    .string()
    .regex(/^\d{7,8}$/, "El DNI debe tener 7 u 8 números"),
});

export type SignupRequest = z.infer<typeof SignupRequestSchema>;

export const LoginResponseSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().nullable(),
});

export type LoginResponse = z.infer<typeof LoginResponseSchema>;
