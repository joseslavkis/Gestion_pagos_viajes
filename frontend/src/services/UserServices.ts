import { useMutation } from "@tanstack/react-query";

import { BASE_API_URL } from "@/config/app-query-client";
import { LoginRequest, LoginResponseSchema, SignupRequest } from "@/models/Login";
import { useToken } from "@/services/TokenContext";
import { handleApiResponse } from "@/services/api-error";

export function useLogin() {
  const [, setToken] = useToken();

  return useMutation({
    mutationFn: async (req: LoginRequest) => {
      const payload = { email: req.username, password: req.password };
      const tokenData = await auth("/api/v1/auth/token", payload);
      setToken({ state: "LOGGED_IN", ...tokenData });
      return tokenData;
    },
  });
}

export function useSignup() {
  const [, setToken] = useToken();

  return useMutation({
    mutationFn: async (req: SignupRequest) => {
      const payload = {
        username: req.username,
        password: req.password,
        email: req.email,
        firstName: req.firstName,
        lastName: req.lastName,
        // Campos requeridos por el backend actual
        name: req.firstName,
        lastname: req.lastName,
        dni: req.dni,
      };
      const tokenData = await auth("/api/v1/auth/signup", payload);
      setToken({ state: "LOGGED_IN", ...tokenData });
      return tokenData;
    },
  });
}

async function auth(endpoint: string, data: Record<string, unknown>) {
  const response = await fetch(BASE_API_URL + endpoint, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(data),
  });

  if (response.ok) {
    return LoginResponseSchema.parse(await response.json());
  } else {
    return handleApiResponse(response);
  }
}
