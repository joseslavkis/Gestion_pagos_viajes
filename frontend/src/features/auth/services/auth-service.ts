import { useMutation } from "@tanstack/react-query";

import { ApiError } from "@/lib/api-error";
import { LoginRequest, LoginResponse, LoginResponseSchema, SignupRequest } from "@/features/auth/types/auth-dtos";
import { useToken } from "@/lib/session";
import { apiPost } from "@/lib/api-client";

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
        studentName: req.studentName,
        studentDni: req.studentDni,
      };

      const tokenData = await apiPost("/api/v1/auth/signup", payload, (json) => LoginResponseSchema.parse(json));
      setToken({ state: "LOGGED_IN", ...tokenData });
      return tokenData;
    },
  });
}

