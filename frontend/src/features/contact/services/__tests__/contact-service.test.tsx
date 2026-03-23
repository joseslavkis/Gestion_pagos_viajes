import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { useSendContactMessage } from "@/features/contact/services/contact-service";
import { server } from "@/test/msw-server";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe("contact-service", () => {
  it("envia el mensaje de contacto y parsea la respuesta", async () => {
    let capturedBody: unknown = null;

    server.use(
      http.post("http://localhost:30002/api/v1/contact/send", async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json({ status: "success", message: "Message queued" }, { status: 202 });
      }),
    );

    const { result } = renderHook(() => useSendContactMessage(), { wrapper: createWrapper() });

    result.current.mutate({
      name: "Jose",
      email: "jose@example.com",
      message: "Hola equipo",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ status: "success", message: "Message queued" });
    expect(capturedBody).toEqual({
      name: "Jose",
      email: "jose@example.com",
      message: "Hola equipo",
    });
  });
});
