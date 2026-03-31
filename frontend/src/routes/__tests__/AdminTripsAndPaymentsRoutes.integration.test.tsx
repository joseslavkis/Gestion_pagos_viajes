import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it } from "vitest";

import { AppRoutes } from "@/routes/AppRoutes";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

function renderAdminRoutes(path = "/") {
  window.history.replaceState({}, "", path);
  return renderWithProviders(<AppRoutes />, "ROLE_ADMIN");
}

afterEach(() => {
  window.history.replaceState({}, "", "/");
});

describe("Admin trips and payments routes integration", () => {
  it("recorre asignacion, listado mixto y desasignacion de un DNI pendiente desde la ruta real", async () => {
    let receivedBulkDnis: string[] = [];
    let studentItems = [
      {
        studentDni: "44555666",
        studentId: 90,
        studentName: "Tomas Benitez",
        schoolName: "Colegio Demo",
        courseName: "5A",
        parentUserId: 9,
        parentFullName: "Clara Benitez",
        parentEmail: "clara@test.com",
        status: "ASSIGNED",
        installmentsCount: 3,
      },
      {
        studentDni: "99888777",
        studentId: null,
        studentName: null,
        schoolName: null,
        courseName: null,
        parentUserId: null,
        parentFullName: null,
        parentEmail: null,
        status: "PENDING",
        installmentsCount: 0,
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
      http.post("http://localhost:30002/api/v1/trips/7/users/bulk", async ({ request }) => {
        const body = await request.json() as { studentDnis: string[] };
        receivedBulkDnis = body.studentDnis;

        return HttpResponse.json({
          status: "OK",
          message: "Asignacion realizada.",
          assignedCount: 1,
          pendingCount: 1,
        });
      }),
      http.get("http://localhost:30002/api/v1/trips/7/students", () => HttpResponse.json(studentItems)),
      http.delete("http://localhost:30002/api/v1/trips/7/students/99888777", () => {
        studentItems = studentItems.filter((item) => item.studentDni !== "99888777");
        return HttpResponse.json({ status: "success", message: "Asignación eliminada" });
      }),
    );

    renderAdminRoutes("/");

    fireEvent.click(await screen.findByRole("button", { name: "Asignar usuarios al viaje Bariloche" }));
    fireEvent.change(screen.getByPlaceholderText("Ej: 45678901, 45678902 o uno por línea"), {
      target: { value: "44.555.666\n99-888-777" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar asignación" }));

    expect(
      await screen.findByText("Asignacion realizada con exito: 1 alumno asignado · 1 DNI pendiente."),
    ).toBeInTheDocument();
    expect(receivedBulkDnis).toEqual(["44555666", "99888777"]);

    fireEvent.click(screen.getByRole("button", { name: "Ver chicos del viaje Bariloche" }));

    const assignedCard = (await screen.findByText("44555666")).closest("article");
    const pendingCard = (await screen.findByText("99888777")).closest("article");

    expect(assignedCard).not.toBeNull();
    expect(pendingCard).not.toBeNull();
    expect(within(assignedCard as HTMLElement).getByText("Reclamado")).toBeInTheDocument();
    expect(within(pendingCard as HTMLElement).getByText("Pendiente")).toBeInTheDocument();

    fireEvent.click(within(pendingCard as HTMLElement).getByRole("button", { name: "Desasignar" }));
    expect(await screen.findByText(/vas a desasignar el DNI 99888777 de este viaje/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Sí, desasignar" }));

    expect(await screen.findByText("El DNI 99888777 fue desasignado del viaje.")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("99888777")).not.toBeInTheDocument());
    expect(screen.getByText("44555666")).toBeInTheDocument();
  });

  it("permite aprobar un comprobante desde /payments/pending-review", async () => {
    let pendingItems = [
      {
        batchId: 91,
        reportedAmount: 200,
        paymentCurrency: "ARS",
        exchangeRate: null,
        amountInTripCurrency: 200,
        reportedPaymentDate: "2026-03-23",
        paymentMethod: "BANK_TRANSFER",
        fileKey: "",
        bankAccountId: 1,
        bankAccountDisplayName: "ICBC - Cuenta en pesos",
        bankAccountAlias: "ICBC.PESOS",
        tripId: 77,
        tripName: "Bariloche",
        tripCurrency: "ARS",
        userId: 9,
        userName: "Jose",
        userLastname: "Slavkis",
        userEmail: "jose@example.com",
        studentName: "Alumno Test",
        studentDni: "45678901",
        receipts: [
          {
            receiptId: 33,
            status: "PENDING",
            reportedAmount: 200,
            amountInTripCurrency: 200,
            installmentId: 12,
            installmentNumber: 4,
            installmentDueDate: "2026-03-25",
            installmentTotalDue: 200,
            adminObservation: null,
          },
        ],
      },
    ];

    server.use(
      http.get("http://localhost:30002/api/v1/trips", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/33/review", () => {
        pendingItems = [];
        return HttpResponse.json({
          id: 33,
          installmentId: 12,
          installmentNumber: 4,
          reportedAmount: 200,
          paymentCurrency: "ARS",
          exchangeRate: null,
          amountInTripCurrency: 200,
          reportedPaymentDate: "2026-03-23",
          paymentMethod: "BANK_TRANSFER",
          status: "APPROVED",
          fileKey: "",
          adminObservation: null,
          bankAccountId: 1,
          bankAccountDisplayName: "ICBC - Cuenta en pesos",
          bankAccountAlias: "ICBC.PESOS",
        });
      }),
    );

    renderAdminRoutes("/payments/pending-review");

    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Desglosar cuotas" }));
    fireEvent.click(screen.getByRole("button", { name: "Aprobar cuota" }));
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });
});
