import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { UserDashboardPage } from "@/features/users/pages/UserDashboardPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("UserDashboardPage", () => {
  it("muestra una notificacion de exito al agregar un alumno", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/my/installments", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/bank-accounts", () => HttpResponse.json([])),
      http.get("http://localhost:30002/api/v1/users/students", () => HttpResponse.json([])),
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

        return HttpResponse.json({
          id: 999,
          ...body,
        }, { status: 201 });
      }),
    );

    renderWithProviders(<UserDashboardPage />);

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
  });

  it("renderiza estados resueltos por backend y permite reportar un pago", async () => {
    let paymentPayload: Record<string, string> | null = null;

    server.use(
      http.get("http://localhost:30002/api/v1/payments/my/installments", () =>
        HttpResponse.json([
          {
            tripId: 77,
            studentId: 501,
            studentName: "Martina Slavkis",
            studentDni: "45678901",
            schoolName: "Colegio Test",
            courseName: "5A",
            installmentId: 101,
            installmentNumber: 1,
            dueDate: "2026-03-25",
            totalDue: 200,
            paidAmount: 0,
            yellowWarningDays: 5,
            tripCurrency: "ARS",
            uiStatusCode: "DUE_SOON",
            uiStatusLabel: "Vence pronto",
            uiStatusTone: "yellow",
            latestReceiptObservation: null,
            userCompletedTrip: false,
          },
          {
            tripId: 77,
            studentId: 501,
            studentName: "Martina Slavkis",
            studentDni: "45678901",
            schoolName: "Colegio Test",
            courseName: "5A",
            installmentId: 102,
            installmentNumber: 2,
            dueDate: "2026-04-25",
            totalDue: 200,
            paidAmount: 0,
            yellowWarningDays: 5,
            tripCurrency: "ARS",
            uiStatusCode: "UNDER_REVIEW",
            uiStatusLabel: "En revisión",
            uiStatusTone: "yellow",
            latestReceiptObservation: null,
            userCompletedTrip: false,
          },
          {
            tripId: 77,
            studentId: 501,
            studentName: "Martina Slavkis",
            studentDni: "45678901",
            schoolName: "Colegio Test",
            courseName: "5A",
            installmentId: 103,
            installmentNumber: 3,
            dueDate: "2026-05-25",
            totalDue: 200,
            paidAmount: 0,
            yellowWarningDays: 5,
            tripCurrency: "ARS",
            uiStatusCode: "RECEIPT_REJECTED",
            uiStatusLabel: "Comprobante rechazado",
            uiStatusTone: "red",
            latestReceiptObservation: "El comprobante está borroso.",
            userCompletedTrip: false,
          },
          {
            tripId: 77,
            studentId: 501,
            studentName: "Martina Slavkis",
            studentDni: "45678901",
            schoolName: "Colegio Test",
            courseName: "5A",
            installmentId: 104,
            installmentNumber: 4,
            dueDate: "2026-06-25",
            totalDue: 200,
            paidAmount: 200,
            yellowWarningDays: 5,
            tripCurrency: "ARS",
            uiStatusCode: "PAID",
            uiStatusLabel: "Pagada",
            uiStatusTone: "green",
            latestReceiptObservation: null,
            userCompletedTrip: false,
          },
          {
            tripId: 77,
            studentId: 501,
            studentName: "Martina Slavkis",
            studentDni: "45678901",
            schoolName: "Colegio Test",
            courseName: "5A",
            installmentId: 105,
            installmentNumber: 5,
            dueDate: "2026-06-26",
            totalDue: 200,
            paidAmount: 50,
            yellowWarningDays: 5,
            tripCurrency: "ARS",
            uiStatusCode: "UP_TO_DATE",
            uiStatusLabel: "Al día",
            uiStatusTone: "green",
            latestReceiptObservation: null,
            userCompletedTrip: false,
          },
        ]),
      ),
      http.get("http://localhost:30002/api/v1/bank-accounts", () =>
        HttpResponse.json([
          {
            id: 1,
            bankName: "ICBC",
            accountLabel: "Cuenta en pesos",
            accountHolder: "Proyecto VA SRL",
            accountNumber: "123",
            taxId: "20-123",
            cbu: "456",
            alias: "ICBC.PESOS",
            currency: "ARS",
            active: true,
            displayOrder: 1,
          },
        ]),
      ),
      http.get("http://localhost:30002/api/v1/users/students", () =>
        HttpResponse.json([
          {
            id: 501,
            name: "Martina Slavkis",
            dni: "45678901",
            schoolName: "Colegio Test",
            courseName: "5A",
          },
        ]),
      ),
      http.get("http://localhost:30002/api/v1/schools", () =>
        HttpResponse.json([
          { id: 10, name: "Colegio Test" },
          { id: 11, name: "Colegio Ward" },
        ]),
      ),
      http.post("http://localhost:30002/api/v1/payments", async ({ request }) => {
        const formData = await request.formData();
        paymentPayload = Object.fromEntries(formData.entries()) as Record<string, string>;
        return HttpResponse.json({
          id: 999,
          installmentId: 101,
          installmentNumber: 1,
          reportedAmount: 250,
          paymentCurrency: "ARS",
          exchangeRate: null,
          amountInTripCurrency: 250,
          reportedPaymentDate: "2026-03-23",
          paymentMethod: "BANK_TRANSFER",
          status: "PENDING",
          fileKey: "",
          adminObservation: null,
          bankAccountId: 1,
          bankAccountDisplayName: "ICBC - Cuenta en pesos",
          bankAccountAlias: "ICBC.PESOS",
        }, { status: 201 });
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    expect(await screen.findByText("Vence pronto")).toBeInTheDocument();
    expect(screen.getByText("En revisión")).toBeInTheDocument();
    expect(screen.getByText("⚠ El comprobante está borroso.")).toBeInTheDocument();
    expect(screen.getByText("Podés reclamar hijos solo si la agencia precargó su DNI en algún viaje.")).toBeInTheDocument();
    expect(
      screen.getByText("Tu comprobante está siendo revisado por el administrador"),
    ).toBeInTheDocument();
    expect(
      screen.getByText((text) => text.includes("Abonado:") && text.includes("Resta:")),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Colegio").tagName).toBe("SELECT");

    fireEvent.change(screen.getByLabelText("Seleccioná el viaje"), {
      target: { value: "77:501" },
    });

    const amountInput = await screen.findByLabelText("Monto pagado");
    fireEvent.change(amountInput, { target: { value: "250.00" } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar comprobante" }));

    await screen.findByText("Comprobante enviado. El administrador lo revisará pronto.");
    await waitFor(() =>
      expect(paymentPayload).toMatchObject({
        installmentId: "101",
        reportedAmount: "250",
        bankAccountId: "1",
      }),
    );
  });
});
