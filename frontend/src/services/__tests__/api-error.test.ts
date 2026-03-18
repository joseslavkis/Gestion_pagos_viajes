import { describe, expect, it } from "vitest";
import { ApiError, handleApiResponse } from "../api-error";

describe("api-error utility", () => {
  it("should create an ApiError instance correctly", () => {
    const error = new ApiError(409, "Friendly message", "Raw message");
    
    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe("ApiError");
    expect(error.status).toBe(409);
    expect(error.message).toBe("Friendly message");
    expect(error.rawMessage).toBe("Raw message");
  });

  describe("handleApiResponse", () => {
    it("should extract JSON message if available", async () => {
      const response = new Response(JSON.stringify({ message: "Email in use" }), {
        status: 409,
        statusText: "Conflict",
      });

      try {
        await handleApiResponse(response);
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(409);
        expect(apiError.rawMessage).toBe("Email in use");
        expect(apiError.message).toBe("El email o documento ingresado ya se encuentra registrado.");
      }
    });

    it("should handle plain text error messages", async () => {
      const response = new Response("Bad Request plain text", {
        status: 400,
      });

      try {
        await handleApiResponse(response);
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(400);
        expect(apiError.rawMessage).toBe("Bad Request plain text");
        expect(apiError.message).toBe("Petición inválida. Verifique los datos ingresados.");
      }
    });

    it("should map common status codes to friendly messages", async () => {
      const testCases = [
        { status: 401, expected: "Credenciales inválidas o sesión expirada." },
        { status: 403, expected: "No tiene permisos para realizar esta acción." },
        { status: 404, expected: "El recurso solicitado no fue encontrado." },
        { status: 500, expected: "Error interno del servidor. Intente nuevamente más tarde." },
        { status: 502, expected: "Error interno del servidor. Intente nuevamente más tarde." },
      ];

      for (const tc of testCases) {
        const response = new Response("", { status: tc.status });
        try {
          await handleApiResponse(response);
        } catch (error) {
          expect((error as ApiError).message).toBe(tc.expected);
        }
      }
    });

    it("should provide default message for unmapped status codes", async () => {
      const response = new Response("", { status: 418 });
      try {
        await handleApiResponse(response);
      } catch (error) {
        expect((error as ApiError).message).toBe("No se pudo completar la solicitud");
      }
    });
  });
});
