import { useMutation } from "@tanstack/react-query";

import { BASE_API_URL } from "@/config/app-query-client";
import { LoginRequest, LoginResponseSchema } from "@/models/Login";
import { useToken } from "@/services/TokenContext";

export function useLogin() {
  const [, setToken] = useToken();

  return useMutation({
    mutationFn: async (req: LoginRequest) => {
      // El backend espera 'email', pero el front usa 'username'
      const payload = { email: req.username, password: req.password };
      const tokenData = await auth("/api/v1/auth/token", payload);
      setToken({ state: "LOGGED_IN", ...tokenData });
    },
  });
}

export function useSignup() {
  const [, setToken] = useToken();

  return useMutation({
    mutationFn: async (req: LoginRequest) => {
      // Generar un DNI aleatorio para no romper la restricción unique = true del backend
      const randomDni = String(Math.floor(Math.random() * 100000000));
      const payload = { 
        email: req.username, 
        password: req.password,
        name: "NombrePrueba",
        lastname: "ApellidoPrueba",
        dni: randomDni
      };
      const tokenData = await auth("/api/v1/auth/signup", payload);
      setToken({ state: "LOGGED_IN", ...tokenData });
    },
  });
}

async function auth(endpoint: string, data: any) {
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
    throw new Error(`Failed with status ${response.status}: ${await response.text()}`);
  }
}
