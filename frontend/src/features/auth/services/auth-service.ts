import { useMutation } from "@tanstack/react-query";

import { ApiError } from "@/lib/api-error";
import {
  ForgotPasswordRequest,
  LoginRequest,
  LoginResponse,
  LoginResponseSchema,
  ResetPasswordRequest,
  SignupRequest,
} from "@/features/auth/types/auth-dtos";
import { StatusResponseDTOSchema, type StatusResponseDTO } from "@/lib/backend-dtos";
import { useToken } from "@/lib/session";
import { apiPost } from "@/lib/api-client";

const RESET_PASSWORD_ERROR_MESSAGES: Record<string, string> = {
  "Este enlace ya fue utilizado.": "Este enlace ya fue utilizado. Solicitá uno nuevo.",
  "El enlace de recuperación no es válido.": "El enlace no es válido. Solicitá uno nuevo.",
  "El enlace de recuperación expiró. Solicitá uno nuevo.": "El enlace expiró. Solicitá uno nuevo.",
};

function mapResetPasswordError(error: unknown): never {
  if (!(error instanceof ApiError)) {
    throw error;
  }

  const rawMessage = error.rawMessage || error.message;
  const mappedMessage = RESET_PASSWORD_ERROR_MESSAGES[rawMessage];

  if (!mappedMessage) {
    throw error;
  }

  throw new ApiError(error.status, mappedMessage, error.rawMessage, error.fieldErrors);
}

export function useLogin() {
  const [, setToken] = useToken();

  return useMutation<LoginResponse, ApiError, LoginRequest>({
    mutationFn: async (req: LoginRequest) => {
      const payload = { email: req.email, password: req.password };
      const tokenData = await apiPost("/api/v1/auth/token", payload, (json) => LoginResponseSchema.parse(json));
      setToken({ state: "LOGGED_IN", ...tokenData });
      return tokenData;
    },
  });
}

export function useSignup() {
  const [, setToken] = useToken();

  return useMutation<LoginResponse, ApiError, SignupRequest>({
    mutationFn: async (req: SignupRequest) => {
      const payload = {
        email: req.email,
        password: req.password,
        name: req.name,
        lastname: req.lastname,
        dni: req.dni,
        phone: req.phone,
        students: req.students,
      };

      const tokenData = await apiPost("/api/v1/auth/signup", payload, (json) => LoginResponseSchema.parse(json));
      setToken({ state: "LOGGED_IN", ...tokenData });
      return tokenData;
    },
  });
}

export function useForgotPassword() {
  return useMutation<StatusResponseDTO, ApiError, ForgotPasswordRequest>({
    mutationFn: async (req: ForgotPasswordRequest) => {
      const payload = { email: req.email };
      return apiPost("/api/v1/auth/forgot-password", payload, (json) => StatusResponseDTOSchema.parse(json));
    },
  });
}

export function useResetPassword() {
  return useMutation<StatusResponseDTO, ApiError, ResetPasswordRequest>({
    mutationFn: async (req: ResetPasswordRequest) => {
      const payload = { token: req.token, newPassword: req.newPassword };

      try {
        return await apiPost("/api/v1/auth/reset-password", payload, (json) => StatusResponseDTOSchema.parse(json));
      } catch (error) {
        mapResetPasswordError(error);
      }
    },
  });
}
