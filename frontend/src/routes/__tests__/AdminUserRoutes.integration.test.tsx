import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it } from "vitest";

import { AppRoutes } from "@/routes/AppRoutes";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

function renderAdminRoutes(path = "/users/search") {
  window.history.replaceState({}, "", path);
  return renderWithProviders(<AppRoutes />, "ROLE_ADMIN");
}

function buildAdminUserDetailResponse(userId: number) {
  return {
    id: userId,
    email: "clara@test.com",
    name: "Clara",
    lastname: "Benitez",
    dni: "33444555",
    phone: "1133344455",
    role: "USER",
    students: [
      {
        id: 88,
        name: "Tomas Benitez",
        dni: "44555666",
        schoolName: "Colegio Demo",
        courseName: "4to",
      },
    ],
    installments: [
      {
        tripId: 9,
        tripName: "Viaje a Mendoza",
        tripCurrency: "ARS",
        studentId: 88,
        studentName: "Tomas Benitez",
        studentDni: "44555666",
        schoolName: "Colegio Demo",
        courseName: "4to",
        installmentId: 100,
        installmentNumber: 1,
        dueDate: "2026-04-10",
        totalDue: 40000,
        paidAmount: 15000,
        installmentStatus: "YELLOW",
        latestReceiptStatus: "APPROVED",
        uiStatusCode: "DUE_SOON",
        uiStatusLabel: "Vence pronto",
        uiStatusTone: "yellow",
        latestReceiptObservation: "Pago verificado",
      },
    ],
    receipts: [
      {
        id: 501,
        installmentId: 100,
        installmentNumber: 1,
        reportedAmount: 15000,
        paymentCurrency: "ARS",
        exchangeRate: null,
        amountInTripCurrency: 15000,
        reportedPaymentDate: "2026-04-02",
        paymentMethod: "BANK_TRANSFER",
        status: "APPROVED",
        fileKey: "https://example.com/comprobante.pdf",
        adminObservation: "Pago verificado",
        bankAccountId: 3,
        bankAccountDisplayName: "Banco Test - Cuenta corriente",
        bankAccountAlias: "agencia.test",
      },
    ],
  };
}

async function wait(ms: number) {
  await new Promise((resolve) => window.setTimeout(resolve, ms));
}

afterEach(() => {
  window.history.replaceState({}, "", "/");
});

describe("Admin user routes integration", () => {
  it("recorre el flujo buscador a detalle desde las rutas reales del admin", async () => {
    let searchRequests = 0;
    let detailRequests = 0;
    let lastSearchAuthHeader: string | null = null;
    let lastDetailAuthHeader: string | null = null;

    server.use(
      http.get("http://localhost:30002/api/v1/users/admin/search", ({ request }) => {
        searchRequests += 1;
        lastSearchAuthHeader = request.headers.get("authorization");
        const query = new URL(request.url).searchParams.get("q");

        if (query === "cl") {
          return HttpResponse.json([
            {
              id: 7,
              email: "clara@test.com",
              name: "Clara",
              lastname: "Benitez",
              dni: "33444555",
              phone: "1133344455",
              role: "USER",
              studentsCount: 2,
            },
          ]);
        }

        return HttpResponse.json([]);
      }),
      http.get("http://localhost:30002/api/v1/users/admin/7/detail", ({ request }) => {
        detailRequests += 1;
        lastDetailAuthHeader = request.headers.get("authorization");
        return HttpResponse.json(buildAdminUserDetailResponse(7));
      }),
    );

    renderAdminRoutes("/users/search");

    expect(screen.getByRole("heading", { name: "Buscar usuarios" })).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText("Buscar por nombre, mail o DNI..."), {
      target: { value: "c" },
    });

    await wait(350);
    expect(searchRequests).toBe(0);

    fireEvent.change(screen.getByPlaceholderText("Buscar por nombre, mail o DNI..."), {
      target: { value: "cl" },
    });

    const userLink = await screen.findByRole("link", { name: /Benitez, Clara/i });
    expect(searchRequests).toBe(1);
    expect(lastSearchAuthHeader).toMatch(/^Bearer /);

    fireEvent.click(userLink);

    await waitFor(() => {
      expect(window.location.pathname).toBe("/users/7");
    });

    expect(await screen.findByRole("heading", { name: "Benitez, Clara" })).toBeInTheDocument();
    expect(screen.getByText("Viaje a Mendoza")).toBeInTheDocument();
    expect(detailRequests).toBe(1);
    expect(lastDetailAuthHeader).toMatch(/^Bearer /);
  });

  it("muestra el estado de error si el admin entra a un detalle inexistente", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/users/admin/999/detail", () =>
        new HttpResponse("User not found with id 999", { status: 404 }),
      ),
    );

    renderAdminRoutes("/users/999");

    expect(await screen.findByText("El recurso solicitado no fue encontrado.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Volver al buscador/i })).toBeInTheDocument();
  });
});
