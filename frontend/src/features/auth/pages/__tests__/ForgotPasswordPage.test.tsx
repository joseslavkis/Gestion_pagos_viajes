import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { ForgotPasswordPage } from "@/features/auth/pages/ForgotPasswordPage";
import { TokenProvider } from "@/lib/session";
import { server } from "@/test/msw-server";

function renderForgotPasswordPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <TokenProvider>
        <ForgotPasswordPage />
      </TokenProvider>
    </QueryClientProvider>,
  );
}

describe("ForgotPasswordPage", () => {
  it("muestra el mensaje de éxito y oculta el formulario tras enviar el email", async () => {
    server.use(
      http.post("http://localhost:30002/api/v1/auth/forgot-password", async () =>
        HttpResponse.json({
          status: "success",
          message: "Si el email existe, recibirás un enlace para restablecer tu contraseña.",
        }),
      ),
    );

    renderForgotPasswordPage();

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "jose@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Enviar enlace" }));

    expect(await screen.findByText(/Si el email está registrado/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Enviar enlace" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Volver al login" })).toBeInTheDocument();
  });
});
