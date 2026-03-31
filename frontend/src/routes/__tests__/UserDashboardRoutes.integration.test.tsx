import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { AppRoutes } from "@/routes/AppRoutes";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("User dashboard routes integration", () => {
  it("reclama un hijo pendiente y refresca alumnos y cuotas", async () => {
    let installmentsRequests = 0;
    let studentItems: Array<{
      id: number;
      name: string;
      dni: string;
      schoolName: string;
      courseName: string;
    }> = [];
    let installmentsItems: Array<Record<string, unknown>> = [];

    server.use(
      http.get("http://localhost:30002/api/v1/payments/my/installments", () => {
        installmentsRequests += 1;
        return HttpResponse.json(installmentsItems);
      }),
      http.get("http://localhost:30002/api/v1/bank-accounts", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/users/students", () => HttpResponse.json(studentItems)),
      http.get("http://localhost:30002/api/v1/schools", () =>
        HttpResponse.json([
          { id: 10, name: "Colegio Test" },
        ]),
      ),
      http.post("http://localhost:30002/api/v1/users/students", async ({ request }) => {
        const body = await request.json() as {
          name: string;
          dni: string;
          schoolName: string;
          courseName: string;
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
            studentName: body.name,
            studentDni: body.dni,
            schoolName: body.schoolName,
            courseName: body.courseName,
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

    window.history.replaceState({}, "", "/");
    renderWithProviders(<AppRoutes />, "ROLE_USER");

    fireEvent.change(await screen.findByLabelText("Nombre completo"), {
      target: { value: "Lucia Perez" },
    });
    fireEvent.change(screen.getByLabelText("DNI"), {
      target: { value: "40111222" },
    });
    fireEvent.change(screen.getByLabelText("Colegio"), {
      target: { value: "Colegio Test" },
    });
    fireEvent.change(screen.getByLabelText("Curso"), {
      target: { value: "5A" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Agregar hijo" }));

    expect(await screen.findByText("El alumno Lucia Perez se agrego con exito.")).toBeInTheDocument();
    expect(await screen.findByText("Lucia Perez")).toBeInTheDocument();
    await waitFor(() => expect(installmentsRequests).toBeGreaterThanOrEqual(2));
    expect(await screen.findByText("DNI alumno: 40111222")).toBeInTheDocument();
  });

  it("muestra el error del backend si intenta reclamar un DNI no habilitado", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/my/installments", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/bank-accounts", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/users/students", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/schools", () =>
        HttpResponse.json([
          { id: 10, name: "Colegio Test" },
        ]),
      ),
      http.post("http://localhost:30002/api/v1/users/students", () =>
        new HttpResponse(
          "El DNI de alumno 40111222 no está habilitado todavía. Pedile a la agencia que lo cargue primero.",
          { status: 409 },
        ),
      ),
    );

    window.history.replaceState({}, "", "/");
    renderWithProviders(<AppRoutes />, "ROLE_USER");

    fireEvent.change(await screen.findByLabelText("Nombre completo"), {
      target: { value: "Lucia Perez" },
    });
    fireEvent.change(screen.getByLabelText("DNI"), {
      target: { value: "40111222" },
    });
    fireEvent.change(screen.getByLabelText("Colegio"), {
      target: { value: "Colegio Test" },
    });
    fireEvent.change(screen.getByLabelText("Curso"), {
      target: { value: "5A" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Agregar hijo" }));

    expect(
      await screen.findByText("El DNI de alumno 40111222 no está habilitado todavía. Pedile a la agencia que lo cargue primero."),
    ).toBeInTheDocument();
  });
});
