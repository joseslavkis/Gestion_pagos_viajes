import { fireEvent, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { AppRoutes } from "@/routes/AppRoutes";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("User dashboard routes integration", () => {
  it("reclama un hijo pendiente y refresca alumnos y cuotas desde la pagina Mis hijos", async () => {
    let studentItems: Array<{
      id: number;
      name: string;
      lastname: string;
      dni: string;
    }> = [];
    let installmentsItems: Array<Record<string, unknown>> = [];

    server.use(
      http.get("http://localhost:30002/api/v1/payments/my/installments", () => HttpResponse.json(installmentsItems)),
      http.get("http://localhost:30002/api/v1/bank-accounts", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/users/students", () => HttpResponse.json(studentItems)),
      http.post("http://localhost:30002/api/v1/users/students", async ({ request }) => {
        const body = await request.json() as {
          name: string;
          lastname: string;
          dni: string;
        };

        studentItems = [
          {
            id: 900,
            ...body,
          },
        ];
        installmentsItems = [
          {
            tripId: 77,
            tripName: "Bariloche",
            tripCurrency: "ARS",
            studentId: 900,
            studentName: `${body.name} ${body.lastname}`,
            studentDni: body.dni,
            installmentId: 101,
            installmentNumber: 1,
            dueDate: "2026-04-10",
            totalDue: 20000,
            paidAmount: 0,
            yellowWarningDays: 5,
            installmentStatus: "YELLOW",
            uiStatusCode: "UP_TO_DATE",
            uiStatusLabel: "Al día",
            uiStatusTone: "green",
            latestReceiptObservation: null,
            userCompletedTrip: false,
          },
        ];

        return HttpResponse.json({
          id: 900,
          ...body,
        }, { status: 201 });
      }),
    );

    // Navigate directly to /mis-hijos
    window.history.replaceState({}, "", "/mis-hijos");
    renderWithProviders(<AppRoutes />, "ROLE_USER");

    fireEvent.change((await screen.findAllByLabelText("Nombre"))[0], {
      target: { value: "Lucia" },
    });
    fireEvent.change(screen.getByLabelText("Apellido"), {
      target: { value: "Perez" },
    });
    fireEvent.change(screen.getByLabelText("DNI"), {
      target: { value: "40111222" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Agregar hijo" }));

    expect(await screen.findByText("El alumno LUCIA PEREZ se agrego con exito.")).toBeInTheDocument();
    expect(await screen.findByText("PEREZ, LUCIA")).toBeInTheDocument();
  });

  it("muestra el error del backend si intenta reclamar un DNI no habilitado", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/users/students", () => HttpResponse.json([])),
      http.post("http://localhost:30002/api/v1/users/students", () =>
        new HttpResponse(
          "El DNI de alumno 40111222 no está habilitado todavía. Pedile a la agencia que lo cargue primero.",
          { status: 409 },
        ),
      ),
    );

    window.history.replaceState({}, "", "/mis-hijos");
    renderWithProviders(<AppRoutes />, "ROLE_USER");

    fireEvent.change((await screen.findAllByLabelText("Nombre"))[0], {
      target: { value: "Lucia" },
    });
    fireEvent.change(screen.getByLabelText("Apellido"), {
      target: { value: "Perez" },
    });
    fireEvent.change(screen.getByLabelText("DNI"), {
      target: { value: "40111222" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Agregar hijo" }));

    expect(
      await screen.findByText("El DNI de alumno 40111222 no está habilitado todavía. Pedile a la agencia que lo cargue primero."),
    ).toBeInTheDocument();
  });
});
