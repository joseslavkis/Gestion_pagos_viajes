import { z } from "zod";

// Backend DTOs (see backend/src/main/java/com/agencia/pagos/dtos/*)

export const UserLoginDTOSchema = z.object({
  email: z.string().email("Debe ser un email válido (ej: nombre@gmail.com)"),
  password: z.string().min(8, "La contraseña debe tener al menos 8 caracteres"),
});

export type UserLoginDTO = z.infer<typeof UserLoginDTOSchema>;

export const UserCreateDTOSchema = z.object({
  email: z.string().email("Debe ser un email válido (ej: nombre@gmail.com)"),
  password: z
    .string()
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&]).{8,}$/,
      "La contraseña debe incluir mayúscula, minúscula, número y símbolo (@$!%*?&)",
    ),
  name: z.string().min(2, "El nombre debe tener al menos 2 caracteres"),
  lastname: z.string().min(2, "El apellido debe tener al menos 2 caracteres"),
  dni: z.string().regex(/^\d{7,8}$/, "El DNI debe tener 7 u 8 números"),
  // Optional fields from backend UserCreateDTO
  phone: z.string().optional(),
  studentName: z.string().optional(),
  schoolName: z.string().optional(),
  courseName: z.string().optional(),
});

export type UserCreateDTO = z.infer<typeof UserCreateDTOSchema>;

export const TokenDTOSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().nullable(),
});

export type TokenDTO = z.infer<typeof TokenDTOSchema>;

// Backward-compatible aliases used by the current UI/forms.
export const LoginRequestSchema = UserLoginDTOSchema;
export const SignupRequestSchema = UserCreateDTOSchema;
export const LoginResponseSchema = TokenDTOSchema;

export type LoginRequest = UserLoginDTO;
export type SignupRequest = UserCreateDTO;
export type LoginResponse = TokenDTO;

