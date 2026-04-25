import { QueryClient, QueryClientProvider, MutationCache } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import userEvent from "@testing-library/user-event";
import { Toaster, toast } from "sonner";

import { AppRoutes } from "@/routes/AppRoutes";
import { TokenProvider } from "@/lib/session";
import { server } from "@/test/msw-server";
import { createJwt } from "@/test/test-utils";

function renderLoggedOutRoutes(path = "/signup") {
  window.history.replaceState({}, "", path);

  const queryClient = new QueryClient({
    mutationCache: new MutationCache({
      onError: (error) => {
        const message = error instanceof Error ? error.message : "Ocurrió un error inesperado al procesar tu solicitud.";
        toast.error(message);
      },
    }),
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <TokenProvider>
        <AppRoutes />
        <Toaster />
      </TokenProvider>
    </QueryClientProvider>,
  );
}

async function completeSignupForm() {
  const user = userEvent.setup();

  await user.type(screen.getAllByLabelText("Nombre")[0], "Clara");
  await user.type(screen.getAllByLabelText("Apellido")[0], "Benitez");
  await user.type(screen.getByLabelText("Email"), "clara@test.com");
  await user.type(screen.getByLabelText("Contraseña"), "Password123!");
  await user.type(screen.getByLabelText("DNI del padre / tutor"), "33444555");
  await user.type(screen.getByLabelText("Teléfono"), "1133344455");
  await user.type(screen.getAllByLabelText("Nombre")[1], "Tomas");
  await user.type(screen.getAllByLabelText("Apellido")[1], "Benitez");
  await user.type(screen.getByLabelText("DNI"), "44555666");

  return user;
}

afterEach(() => {
  window.history.replaceState({}, "", "/");
});

describe("Auth routes integration", () => {
  it("recorre signup exitoso y entra al dashboard del usuario", async () => {
    let signupBody: unknown = null;

    server.use(
      http.post("http://localhost:30002/api/v1/auth/signup", async ({ request }) => {
        signupBody = await request.json();
        return HttpResponse.json({
          accessToken: createJwt("ROLE_USER"),
          refreshToken: "refresh-token",
        }, { status: 201 });
      }),
      http.get("http://localhost:30002/api/v1/payments/my/installments", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/bank-accounts", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/users/students", () =>
        HttpResponse.json([
          {
            id: 77,
            name: "Tomas",
            lastname: "Benitez",
            dni: "44555666",
          },
        ]),
      ),
    );

    renderLoggedOutRoutes("/signup");

    const user = await completeSignupForm();
    await user.click(screen.getByRole("button", { name: "Crear cuenta" }));

    await screen.findByRole("heading", { name: "Panel de pagos" });
    await waitFor(() =>
      expect(signupBody).toEqual({
        email: "clara@test.com",
        password: "Password123!",
        name: "Clara",
        lastname: "Benitez",
        dni: "33444555",
        phone: "1133344455",
        students: [
          {
            name: "TOMAS",
            lastname: "BENITEZ",
            dni: "44555666",
          },
        ],
      }),
    );
  }, 15000);

  it("muestra el error del backend si el DNI del hijo no fue precargado", async () => {
    server.use(
      http.post("http://localhost:30002/api/v1/auth/signup", () =>
        new HttpResponse(
          "El DNI de alumno 44555666 no está habilitado todavía. Pedile a la agencia que lo cargue primero.",
          { status: 409 },
        ),
      ),
    );

    renderLoggedOutRoutes("/signup");

    const user = await completeSignupForm();
    await user.click(screen.getByRole("button", { name: "Crear cuenta" }));

    expect(
      await screen.findByText("El DNI de alumno 44555666 no está habilitado todavía. Pedile a la agencia que lo cargue primero."),
    ).toBeInTheDocument();
  }, 15000);
});
