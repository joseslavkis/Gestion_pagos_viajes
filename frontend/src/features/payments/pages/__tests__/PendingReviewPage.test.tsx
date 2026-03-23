import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { PendingReviewPage } from "@/features/payments/pages/PendingReviewPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("PendingReviewPage", () => {
  it("lista pendientes y permite aprobar desde la bandeja", async () => {
    let decisionBody: unknown = null;
    let pendingItems = [
      {
        receiptId: 33,
        status: "PENDING",
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
        installmentId: 12,
        installmentNumber: 4,
        installmentDueDate: "2026-03-25",
        installmentTotalDue: 200,
        tripId: 77,
        tripName: "Bariloche",
        tripCurrency: "ARS",
        userId: 9,
        userName: "Jose",
        userLastname: "Slavkis",
        userEmail: "jose@example.com",
        studentName: "Alumno Test",
        studentDni: "45678901",
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

    fireEvent.click(screen.getByRole("button", { name: "Aprobar" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({ decision: "APPROVED" });
    });
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });
});
