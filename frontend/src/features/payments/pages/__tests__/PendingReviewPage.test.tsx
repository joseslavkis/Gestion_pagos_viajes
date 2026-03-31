import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { PendingReviewPage } from "@/features/payments/pages/PendingReviewPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

function makePendingBatch() {
  return {
    batchId: 91,
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
      {
        receiptId: 34,
        status: "PENDING",
        reportedAmount: 200,
        amountInTripCurrency: 200,
        installmentId: 13,
        installmentNumber: 5,
        installmentDueDate: "2026-04-25",
        installmentTotalDue: 200,
        adminObservation: null,
      },
    ],
  };
}

describe("PendingReviewPage", () => {
  it("lista pendientes agrupados y permite aprobar una cuota desglosada", async () => {
    let decisionBody: unknown = null;
    let pendingItems = [makePendingBatch()];

    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/33/review", async ({ request }) => {
        decisionBody = await request.json();
        pendingItems = [
          {
            ...pendingItems[0],
            receipts: [pendingItems[0].receipts[1]],
            reportedAmount: 200,
            amountInTripCurrency: 200,
          },
        ];
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

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");

    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    expect(screen.getByText("ICBC - Cuenta en pesos · ICBC.PESOS")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Desglosar cuotas" }));

    expect(await screen.findByText("Cuota #4")).toBeInTheDocument();
    expect(screen.getByText("Cuota #5")).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "Aprobar cuota" })[0]);

    await waitFor(() => {
      expect(decisionBody).toEqual({ decision: "APPROVED" });
    });
    expect(await screen.findByText("Cuota #5")).toBeInTheDocument();
    expect(screen.queryByText("Cuota #4")).not.toBeInTheDocument();
  });

  it("permite rechazar una cuota desglosada con observacion", async () => {
    let decisionBody: unknown = null;
    let pendingItems = [
      {
        ...makePendingBatch(),
        receipts: [makePendingBatch().receipts[0]],
        reportedAmount: 200,
        amountInTripCurrency: 200,
      },
    ];

    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/33/review", async ({ request }) => {
        decisionBody = await request.json();
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
          status: "REJECTED",
          fileKey: "",
          adminObservation: "Comprobante borroso",
          bankAccountId: 1,
          bankAccountDisplayName: "ICBC - Cuenta en pesos",
          bankAccountAlias: "ICBC.PESOS",
        });
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");

    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Desglosar cuotas" }));
    fireEvent.click(screen.getByRole("button", { name: "Rechazar cuota" }));
    fireEvent.change(screen.getByPlaceholderText("Observación obligatoria para rechazar"), {
      target: { value: "Comprobante borroso" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar rechazo" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        decision: "REJECTED",
        adminObservation: "Comprobante borroso",
      });
    });
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });
});
