import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { PendingReviewPage } from "@/features/payments/pages/PendingReviewPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

function makePendingSubmission(reportedAmount = "400.00") {
  return {
    submissionId: 91,
    status: "PENDING",
    reportedAmount,
    paymentCurrency: "ARS",
    exchangeRate: null,
    amountInTripCurrency: reportedAmount,
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
        totalDue: "200.00",
        paidAmount: "0.00",
        remainingAmount: "200.00",
        reportedAmount: "200.00",
        amountInTripCurrency: "200.00",
        status: "PENDING",
      },
      {
        receiptId: null,
        installmentId: 13,
        installmentNumber: 5,
        dueDate: "2026-04-25",
        totalDue: "200.00",
        paidAmount: "0.00",
        remainingAmount: "200.00",
        reportedAmount: "200.00",
        amountInTripCurrency: "200.00",
        status: "PENDING",
      },
    ],
  };
}

function approvedSubmissionResponse(overrides: Record<string, unknown>) {
  return {
    submissionId: 91,
    status: "APPROVED",
    reportedAmount: "400.00",
    approvedAmount: "400.00",
    rejectedAmount: "0.00",
    paymentCurrency: "ARS",
    exchangeRate: null,
    amountInTripCurrency: "400.00",
    approvedAmountInTripCurrency: "400.00",
    reportedPaymentDate: "2026-03-23",
    paymentMethod: "BANK_TRANSFER",
    fileKey: "",
    adminObservation: null,
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
    ...overrides,
  };
}

describe("PendingReviewPage", () => {
  it("preserves invalid approval text, disables saving, and sends no review request", async () => {
    let reviewRequests = 0;
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission()]),
      ),
      http.patch("http://localhost:30002/api/v1/payments/91/review", () => {
        reviewRequests += 1;
        return HttpResponse.json({});
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    const saveButton = screen.getByRole("button", { name: "Guardar decisión" });
    for (const invalidAmount of ["250.", "abc", "-1", "1.005"]) {
      fireEvent.change(amountInput, { target: { value: invalidAmount } });

      expect(amountInput).toHaveValue(invalidAmount);
      expect(saveButton).toBeDisabled();
      expect(screen.getByRole("alert")).toHaveTextContent(/monto válido/i);
      expect(reviewRequests).toBe(0);
    }
  });

  it("allows approval above the reported amount and marks an upward correction", async () => {
    let reviewRequests = 0;
    let decisionBody: unknown = null;
    let pendingItems = [makePendingSubmission()];
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
        reviewRequests += 1;
        decisionBody = await request.json();
        pendingItems = [];
        return HttpResponse.json(
          approvedSubmissionResponse({
            status: "APPROVED",
            approvedAmount: "500.00",
            rejectedAmount: "0.00",
            approvedAmountInTripCurrency: "500.00",
            adminObservation: "El banco acreditó más de lo informado.",
          }),
        );
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    fireEvent.change(amountInput, { target: { value: "500" } });

    expect(amountInput).toHaveValue("500");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();
    // Correcting the amount requires an observation before saving.
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeDisabled();
    expect(reviewRequests).toBe(0);

    fireEvent.change(screen.getByLabelText(/Observación admin/), {
      target: { value: "El banco acreditó más de lo informado." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar decisión" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: "500",
        adminObservation: "El banco acreditó más de lo informado.",
      });
    });
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });

  it("rejects amounts above the persistible money ceiling without a request", async () => {
    let reviewRequests = 0;
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission()]),
      ),
      http.patch("http://localhost:30002/api/v1/payments/91/review", () => {
        reviewRequests += 1;
        return HttpResponse.json({});
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    fireEvent.change(amountInput, { target: { value: "100000000" } });

    expect(amountInput).toHaveValue("100000000");
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("99.999.999,99");
    expect(reviewRequests).toBe(0);
  });

  it("accepts the maximum persistible amount with an upward correction", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission()]),
      ),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    fireEvent.change(amountInput, { target: { value: "99999999.99" } });

    expect(amountInput).toHaveValue("99999999.99");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeDisabled();
  });

  it("caps the admin observation at 500 characters", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission()]),
      ),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));

    expect(screen.getByLabelText(/Observación admin/)).toHaveAttribute("maxLength", "500");
  });

  it("shows downward correction and neutral state without a request", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission()]),
      ),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ver imputación y decidir" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    fireEvent.change(amountInput, { target: { value: "250" } });
    expect(await screen.findByText("Corrección a la baja")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeDisabled();

    fireEvent.change(amountInput, { target: { value: "400.00" } });
    expect(await screen.findByText("Sin corrección")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar decisión" })).not.toBeDisabled();
  });

  it("quick approve sends exactly the reported amount", async () => {
    let decisionBody: unknown = null;
    let pendingItems = [makePendingSubmission()];
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
        decisionBody = await request.json();
        pendingItems = [];
        return HttpResponse.json(approvedSubmissionResponse({}));
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Aprobar monto informado" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({ approvedAmount: "400.00" });
    });
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });

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
          reportedAmount: "400.00",
          approvedAmount: "250.00",
          rejectedAmount: "150.00",
          paymentCurrency: "ARS",
          exchangeRate: null,
          amountInTripCurrency: "400.00",
          approvedAmountInTripCurrency: "250.00",
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

    fireEvent.change(screen.getByLabelText("Monto a imputar"), {
      target: { value: "250" },
    });
    fireEvent.change(screen.getByLabelText(/Observación admin/), {
      target: { value: "Se aprobó el monto verificado." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar decisión" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: "250",
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
          reportedAmount: "400.00",
          approvedAmount: "0.00",
          rejectedAmount: "400.00",
          paymentCurrency: "ARS",
          exchangeRate: null,
          amountInTripCurrency: "400.00",
          approvedAmountInTripCurrency: "0.00",
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
    fireEvent.change(screen.getByLabelText(/Observación admin/), {
      target: { value: "Comprobante borroso" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Rechazar total" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: "0",
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

    expect(await screen.findByAltText("Comprobante 1")).toHaveAttribute(
      "src",
      "https://backend.example/api/v1/payment-attachments/receipt.jpg?token=abc",
    );
  });
});
