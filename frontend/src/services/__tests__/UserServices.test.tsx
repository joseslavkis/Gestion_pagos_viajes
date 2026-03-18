// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useLogin, useSignup } from "../UserServices";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError } from "../api-error";
import { ReactNode } from "react";

// Mock del contexto del token y variable global de entorno
vi.mock("@/services/TokenContext", () => ({
  useToken: () => [null, vi.fn()],
}));

vi.mock("@/config/app-query-client", () => ({
  BASE_API_URL: "http://localhost:8080",
}));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
});

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

describe("UserServices Hook Error Handling", () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
    queryClient.clear();
  });

  describe("useSignup", () => {
    it("should throw a 409 ApiError when email is taken", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Email already taken" }), {
          status: 409,
          headers: { "Content-Type": "application/json" }
        })
      );

      const { result } = renderHook(() => useSignup(), { wrapper });

      result.current.mutate({
        username: "test",
        email: "test@example.com",
        password: "password123",
        firstName: "Test",
        lastName: "User",
        dni: "12345678"
      });

      await waitFor(() => expect(result.current.isError).toBe(true));
      
      const error = result.current.error as ApiError;
      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(409);
      expect(error.message).toBe("El email o documento ingresado ya se encuentra registrado.");
    });
  });

  describe("useLogin", () => {
    it("should throw a 401 ApiError on invalid credentials", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Invalid credentials" }), {
          status: 401,
          headers: { "Content-Type": "application/json" }
        })
      );

      const { result } = renderHook(() => useLogin(), { wrapper });

      result.current.mutate({
        username: "test",
        password: "wrong-password",
      });

      await waitFor(() => expect(result.current.isError).toBe(true));
      
      const error = result.current.error as ApiError;
      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(401);
      expect(error.message).toBe("Credenciales inválidas o sesión expirada.");
    });
  });
});
