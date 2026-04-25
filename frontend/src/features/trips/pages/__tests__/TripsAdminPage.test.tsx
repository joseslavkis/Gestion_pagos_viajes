import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { TripsAdminPage } from "@/features/trips/pages/TripsAdminPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("TripsAdminPage", () => {

  it("crea un viaje enviando primera cuota y muestra disclaimer de cuotas restantes", async () => {
    let createPayload: Record<string, unknown> | null = null;

    server.use(
      http.get("http://localhost:30002/api/v1/trips", () => HttpResponse.json([])),
      http.post("http://localhost:30002/api/v1/trips", async ({ request }) => {
        createPayload = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            id: 99,
            name: "Bariloche 2027",
            totalAmount: 1000,
            firstInstallmentAmount: 300,
            currency: "ARS",
            installmentsCount: 4,
            dueDay: 10,
            yellowWarningDays: 5,
            fixedFineAmount: 0,
            retroactiveActive: false,
            firstDueDate: "2027-01-10",
            assignedUsersCount: 0,
            assignedParticipantsCount: 0,
          },
          { status: 201 },
        );
      }),
    );

    renderWithProviders(<TripsAdminPage />);

    fireEvent.click(await screen.findByRole("button", { name: /crear primer viaje/i }));
    fireEvent.change(screen.getByLabelText("Nombre del viaje"), { target: { value: "Bariloche 2027" } });
    fireEvent.change(screen.getByLabelText("Monto total"), { target: { value: "1000" } });
    fireEvent.change(screen.getByLabelText("Primera cuota"), { target: { value: "300" } });
    fireEvent.change(screen.getByLabelText("Cantidad de cuotas"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Día de vencimiento"), { target: { value: "10" } });
    fireEvent.change(screen.getByLabelText("Días de aviso amarillo"), { target: { value: "5" } });
    fireEvent.change(screen.getByLabelText("Recargo fijo por mora"), { target: { value: "0" } });
    fireEvent.change(screen.getByLabelText("Primera fecha de vencimiento"), { target: { value: "2027-01-10" } });

    expect(await screen.findByText(/Las demás cuotas serán de/i)).toHaveTextContent("$ 234,00");

    fireEvent.click(screen.getByRole("button", { name: "Crear viaje" }));

    await waitFor(() => expect(createPayload).not.toBeNull());
    expect(createPayload).toMatchObject({
      name: "Bariloche 2027",
      totalAmount: 1000,
      firstInstallmentAmount: 300,
      installmentsCount: 4,
    });
  });
  it("muestra el error del backend con el DNI rechazado si ya estaba cargado en el viaje", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/trips", () =>
        HttpResponse.json([
          {
            id: 7,
            name: "Bariloche",
            totalAmount: 1500000,
            currency: "ARS",
            installmentsCount: 12,
            assignedUsersCount: 0,
            assignedParticipantsCount: 0,
          },
        ]),
      ),
      http.post("http://localhost:30002/api/v1/trips/7/users/bulk", async () =>
        new HttpResponse("El DNI 46113387 ya está cargado en este viaje y fue rechazado.", { status: 409 }),
      ),
    );

    renderWithProviders(<TripsAdminPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Asignar usuarios al viaje Bariloche" }));
    fireEvent.change(screen.getByPlaceholderText("Ej: 45678901, 45678902 o uno por línea"), {
      target: { value: "46113387" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar asignación" }));

    expect(
      await screen.findByText("El DNI 46113387 ya está cargado en este viaje y fue rechazado."),
    ).toBeInTheDocument();
  });

  it("muestra un error si se repite un DNI en la asignacion", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/trips", () =>
        HttpResponse.json([
          {
            id: 7,
            name: "Bariloche",
            totalAmount: 1500000,
            currency: "ARS",
            installmentsCount: 12,
            assignedUsersCount: 0,
            assignedParticipantsCount: 0,
          },
        ]),
      ),
    );

    renderWithProviders(<TripsAdminPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Asignar usuarios al viaje Bariloche" }));

    fireEvent.change(screen.getByPlaceholderText("Ej: 45678901, 45678902 o uno por línea"), {
      target: { value: "45678901\n45678901" },
    });

    expect(await screen.findByText("Los DNIs no deben repetirse")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirmar asignación" })).toBeDisabled();
  });

  it("muestra una notificacion de exito despues de asignar usuarios", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/trips", () =>
        HttpResponse.json([
          {
            id: 7,
            name: "Bariloche",
            totalAmount: 1500000,
            currency: "ARS",
            installmentsCount: 12,
            assignedUsersCount: 0,
            assignedParticipantsCount: 0,
          },
        ]),
      ),
      http.post("http://localhost:30002/api/v1/trips/7/users/bulk", async () =>
        HttpResponse.json({
          status: "OK",
          message: "Asignacion realizada.",
          assignedCount: 2,
          pendingCount: 1,
        }),
      ),
    );

    renderWithProviders(<TripsAdminPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Asignar usuarios al viaje Bariloche" }));

    fireEvent.change(screen.getByPlaceholderText("Ej: 45678901, 45678902 o uno por línea"), {
      target: { value: "45678901\n45678902\n45678903" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Confirmar asignación" }));

    expect(
      await screen.findByText("Asignacion realizada con exito: 2 alumnos asignados · 1 DNI pendiente."),
    ).toBeInTheDocument();
  });

  it("permite ver y desasignar chicos del viaje con doble confirmacion", async () => {
    let studentItems = [
      {
        studentDni: "46113387",
        studentId: 12,
        studentName: "Luca Perez",
        parentUserId: 90,
        parentFullName: "Ana Perez",
        parentEmail: "ana@test.com",
        status: "ASSIGNED",
        installmentsCount: 3,
      },
    ];

    server.use(
      http.get("http://localhost:30002/api/v1/trips", () =>
        HttpResponse.json([
          {
            id: 7,
            name: "Bariloche",
            totalAmount: 1500000,
            currency: "ARS",
            installmentsCount: 12,
            assignedUsersCount: 1,
            assignedParticipantsCount: 1,
          },
        ]),
      ),
      http.get("http://localhost:30002/api/v1/trips/7/students", () => HttpResponse.json(studentItems)),
      http.delete("http://localhost:30002/api/v1/trips/7/students/46113387", () => {
        studentItems = [];
        return HttpResponse.json({ status: "success", message: "Asignación eliminada" });
      }),
    );

    renderWithProviders(<TripsAdminPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Ver chicos del viaje Bariloche" }));

    expect(await screen.findByText("46113387")).toBeInTheDocument();
    expect(screen.getByText("Luca Perez")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Desasignar" }));

    expect(
      await screen.findByText(/vas a desasignar el DNI 46113387 de este viaje/i),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Sí, desasignar" }));

    expect(await screen.findByText("El DNI 46113387 fue desasignado del viaje.")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByText("Todavía no hay DNIs cargados en este viaje.")).toBeInTheDocument(),
    );
  });
});
