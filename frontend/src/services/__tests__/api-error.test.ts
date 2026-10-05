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
    const uploadFallback = "Los archivos adjuntos superan el tamaño permitido.";
    const uploadMessage = `${uploadFallback} Reduzca su tamaño e intente nuevamente.`;

    it.each([
      ["JSON", JSON.stringify({ message: uploadMessage }), "application/json"],
      ["plain text", uploadMessage, "text/plain"],
    ])("preserves a readable 413 %s message", async (_format, body, contentType) => {
      const response = new Response(body, { status: 413, headers: { "Content-Type": contentType } });
      await expect(handleApiResponse(response)).rejects.toMatchObject({
        name: "ApiError", status: 413, message: uploadMessage, rawMessage: uploadMessage,
      });
    });

    it.each(["plain text", "JSON"])("preserves actionable multiline 413 %s prose", async (format) => {
      const message = `${uploadFallback}\nReduzca su tamaño e intente nuevamente.`;
      const body = format === "JSON" ? JSON.stringify({ message }) : message;
      await expect(handleApiResponse(new Response(body, { status: 413 }))).rejects.toMatchObject({
        name: "ApiError", status: 413, message, rawMessage: message,
      });
    });

    it.each([
      ["empty", ""],
      ["whitespace", "   "],
      ["HTML", "<html><body>413 Request Entity Too Large</body></html>"],
      ["JSON HTML", JSON.stringify({ message: "<h1>413 Request Entity Too Large</h1>" })],
      ["encoded HTML", "&lt;h1&gt;413 Request Entity Too Large&lt;/h1&gt;"],
      ["exception", JSON.stringify({ message: "MaxUploadSizeExceededException" })],
      ["stack trace", "java.lang.IllegalStateException: upload failed\n at com.agencia.Upload.run(Upload.java:42)"],
      ["SQL", JSON.stringify({ message: "Hibernate: SQL select * from payment_submission" })],
      ["lowercase SQL", "select * from payment_submission"],
      ["nginx plain text", "nginx: client intended to send too large body"],
      ["nginx JSON", JSON.stringify({ message: "nginx: client intended to send too large body" })],
      ["internal code", JSON.stringify({ message: "FIN-001: reportedAmount exceeds maxAllowedAmount" })],
      ["internal identifier", "reportedAmount supera maxAllowedAmount"],
      ["internal class", "PaymentAllocationPlanner"],
      ["unsupported JSON", JSON.stringify({ status: 413, error: "Payload Too Large" })],
      ["JSON null", "null"],
      ["JSON number", "413"],
      ["malformed JSON", '{"message":'],
    ])("uses the exact 413 fallback for %s bodies", async (_kind, body) => {
      await expect(handleApiResponse(new Response(body, { status: 413 }))).rejects.toMatchObject({
        name: "ApiError", status: 413, message: uploadFallback,
      });
    });

    it("uses the exact 413 fallback when the response body cannot be read", async () => {
      const response = new Response(uploadMessage, { status: 413 });
      await response.text();
      await expect(handleApiResponse(response)).rejects.toMatchObject({
        name: "ApiError", status: 413, message: uploadFallback, rawMessage: "",
      });
    });

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
        expect(apiError.message).toBe("Email in use");
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
        expect(apiError.message).toBe("Bad Request plain text");
      }
    });

    it("extrae el primer mensaje del array errors en un 400 de validación Bean", async () => {
      const response = new Response(
        JSON.stringify({ errors: ["name: size must be between 2 and 100"] }),
        {
          status: 400,
          headers: { "Content-Type": "application/json" },
        },
      );

      try {
        await handleApiResponse(response);
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(400);
        expect(apiError.rawMessage).toContain("name: size must be between 2 and 100");
        expect(apiError.message).toBe("name: size must be between 2 and 100");
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
