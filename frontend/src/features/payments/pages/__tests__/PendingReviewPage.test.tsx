import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { PendingReviewPage } from "@/features/payments/pages/PendingReviewPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

function makePendingSubmission() {
  return {
    submissionId: 91,
    status: "PENDING",
    reportedAmount: 400,
    paymentCurrency: "ARS",
    exchangeRate: null,
    amountInTripCurrency: 400,
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
    allocations: [
      {
        receiptId: null,
        installmentId: 12,
        installmentNumber: 4,
        dueDate: "2026-03-25",
        totalDue: 200,
        paidAmount: 0,
        remainingAmount: 200,
        reportedAmount: 200,
        amountInTripCurrency: 200,
        status: "PENDING",
      },
      {
        receiptId: null,
        installmentId: 13,
        installmentNumber: 5,
        dueDate: "2026-04-25",
        totalDue: 200,
        paidAmount: 0,
        remainingAmount: 200,
        reportedAmount: 200,
        amountInTripCurrency: 200,
        status: "PENDING",
      },
    ],
  };
}

describe("PendingReviewPage", () => {
  it("lista pagos pendientes y permite aprobarlos parcialmente", async () => {
    let decisionBody: unknown = null;
    let pendingItems = [makePendingSubmission()];

    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
        decisionBody = await request.json();
        pendingItems = [];
        return HttpResponse.json({
          submissionId: 91,
          status: "PARTIALLY_APPROVED",
          reportedAmount: 400,
          approvedAmount: 250,
          rejectedAmount: 150,
          paymentCurrency: "ARS",
          exchangeRate: null,
          amountInTripCurrency: 400,
          approvedAmountInTripCurrency: 250,
          reportedPaymentDate: "2026-03-23",
          paymentMethod: "BANK_TRANSFER",
          fileKey: "",
          adminObservation: "Se aprobó el monto verificado.",
          bankAccountId: 1,
          bankAccountDisplayName: "ICBC - Cuenta en pesos",
          bankAccountAlias: "ICBC.PESOS",
          tripId: 77,
          tripName: "Bariloche",
          tripCurrency: "ARS",
          studentId: 5,
          studentName: "Alumno Test",
          studentDni: "45678901",
          installments: [],
        });
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");

    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    expect(screen.getByText("ICBC - Cuenta en pesos · ICBC.PESOS")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));

    expect(await screen.findByText("Cuota #4")).toBeInTheDocument();
    expect(screen.getByText("Cuota #5")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Monto a aprobar"), {
      target: { value: "250" },
    });
    fireEvent.change(screen.getByLabelText("Observación admin"), {
      target: { value: "Se aprobó el monto verificado." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar decisión" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: 250,
        adminObservation: "Se aprobó el monto verificado.",
      });
    });
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });

  it("permite rechazar un pago completo con observacion", async () => {
    let decisionBody: unknown = null;
    let pendingItems = [makePendingSubmission()];

    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
        decisionBody = await request.json();
        pendingItems = [];
        return HttpResponse.json({
          submissionId: 91,
          status: "REJECTED",
          reportedAmount: 400,
          approvedAmount: 0,
          rejectedAmount: 400,
          paymentCurrency: "ARS",
          exchangeRate: null,
          amountInTripCurrency: 400,
          approvedAmountInTripCurrency: 0,
          reportedPaymentDate: "2026-03-23",
          paymentMethod: "BANK_TRANSFER",
          fileKey: "",
          adminObservation: "Comprobante borroso",
          bankAccountId: 1,
          bankAccountDisplayName: "ICBC - Cuenta en pesos",
          bankAccountAlias: "ICBC.PESOS",
          tripId: 77,
          tripName: "Bariloche",
          tripCurrency: "ARS",
          studentId: 5,
          studentName: "Alumno Test",
          studentDni: "45678901",
          installments: [],
        });
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");

    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));
    fireEvent.change(screen.getByLabelText("Observación admin"), {
      target: { value: "Comprobante borroso" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Rechazar total" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: 0,
        adminObservation: "Comprobante borroso",
      });
    });
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });

  it("muestra preview de imagen cuando el comprobante viene como URL remota", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([
          {
            ...makePendingSubmission(),
            fileKey: "https://backend.example/api/v1/payment-attachments/receipt.jpg?token=abc",
          },
        ]),
      ),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");

    expect(await screen.findByAltText("Comprobante")).toHaveAttribute(
      "src",
      "https://backend.example/api/v1/payment-attachments/receipt.jpg?token=abc",
    );
  });
});
