import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it } from "vitest";

import { ResetPasswordPage } from "@/features/auth/pages/ResetPasswordPage";
import { TokenProvider } from "@/lib/session";
import { server } from "@/test/msw-server";

function renderResetPasswordPage(path = "/reset-password") {
  window.history.replaceState({}, "", path);

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <TokenProvider>
        <ResetPasswordPage />
      </TokenProvider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  window.history.replaceState({}, "", "/");
});

describe("ResetPasswordPage", () => {
  it("muestra un error si no hay token en la URL", () => {
    renderResetPasswordPage("/reset-password");

    expect(screen.getByText("El enlace no es válido. Solicitá uno nuevo desde el login.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Solicitar nuevo enlace" })).toBeInTheDocument();
  });

  it("valida que ambas contraseñas coincidan", async () => {
    renderResetPasswordPage("/reset-password?token=token-ok");

    fireEvent.change(screen.getByLabelText("Nueva contraseña"), {
      target: { value: "NuevaPassword123!" },
    });
    fireEvent.change(screen.getByLabelText("Confirmar contraseña"), {
      target: { value: "OtraPassword123!" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Actualizar contraseña" }));

    expect(await screen.findByText("Las contraseñas no coinciden")).toBeInTheDocument();
  });

  it("traduce el error del backend cuando el enlace ya fue utilizado", async () => {
    server.use(
      http.post("http://localhost:30002/api/v1/auth/reset-password", async () =>
        new HttpResponse("Este enlace ya fue utilizado.", { status: 409 }),
      ),
    );

    renderResetPasswordPage("/reset-password?token=token-usado");

    fireEvent.change(screen.getByLabelText("Nueva contraseña"), {
      target: { value: "NuevaPassword123!" },
    });
    fireEvent.change(screen.getByLabelText("Confirmar contraseña"), {
      target: { value: "NuevaPassword123!" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Actualizar contraseña" }));

    expect(await screen.findByText("Este enlace ya fue utilizado. Solicitá uno nuevo.")).toBeInTheDocument();
  });

  it("muestra el estado de éxito cuando la contraseña se actualiza", async () => {
    server.use(
      http.post("http://localhost:30002/api/v1/auth/reset-password", async () =>
        HttpResponse.json({
          status: "success",
          message: "Contraseña actualizada correctamente.",
        }),
      ),
    );

    renderResetPasswordPage("/reset-password?token=token-ok");

    fireEvent.change(screen.getByLabelText("Nueva contraseña"), {
      target: { value: "NuevaPassword123!" },
    });
    fireEvent.change(screen.getByLabelText("Confirmar contraseña"), {
      target: { value: "NuevaPassword123!" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Actualizar contraseña" }));

    expect(await screen.findByText("¡Contraseña actualizada! Ya podés iniciar sesión.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ir al login" })).toBeInTheDocument();
  });
});
