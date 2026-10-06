import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { PendingReviewPage } from "@/features/payments/pages/PendingReviewPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

function makePendingSubmission(reportedAmount = "400.00", overrides: Record<string, unknown> = {}) {
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
        allocationCurrency: overrides.paymentCurrency ?? "ARS",
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
        allocationCurrency: overrides.paymentCurrency ?? "ARS",
      },
    ],
    ...overrides,
  };
}

function approvedSubmissionResponse(overrides: Record<string, unknown>) {
  return {
    approvedCurrency: "ARS", approvedExchangeRate: null, approvedQuoteRequestedDate: null,
    approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
    approvedQuoteProviderTimestamp: null, approvedCalculationVersion: "2", rejectedCurrency: null,
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
    source: "CUSTOMER_SUBMISSION",
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
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

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
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    fireEvent.change(amountInput, { target: { value: "500" } });

    expect(amountInput).toHaveValue("500");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeEnabled();
    expect(reviewRequests).toBe(0);

    fireEvent.change(screen.getByLabelText(/Observación/), {
      target: { value: "El banco acreditó más de lo informado." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar decisión" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: "500",
        approvedCurrency: "ARS",
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
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

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
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    fireEvent.change(amountInput, { target: { value: "99999999.99" } });

    expect(amountInput).toHaveValue("99999999.99");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeEnabled();
  });

  it("caps the admin observation at 500 characters", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission()]),
      ),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    expect(screen.getByLabelText(/Observación/)).toHaveAttribute("maxLength", "500");
  });

  it("shows downward correction and neutral state without a request", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission()]),
      ),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    fireEvent.change(amountInput, { target: { value: "250" } });
    expect(await screen.findByText("Corrección a la baja")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeEnabled();

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
      expect(decisionBody).toEqual({ approvedAmount: "400.00", approvedCurrency: "ARS" });
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
          approvedCurrency: "ARS", approvedExchangeRate: null, approvedQuoteRequestedDate: null,
          approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
          approvedQuoteProviderTimestamp: null, approvedCalculationVersion: "2", rejectedCurrency: "ARS",
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
          source: "CUSTOMER_SUBMISSION",
        });
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");

    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    expect(screen.getByText("ICBC - Cuenta en pesos · ICBC.PESOS")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    expect(await screen.findByText("Cuota #4")).toBeInTheDocument();
    expect(screen.getByText("Cuota #5")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Monto a imputar"), {
      target: { value: "250" },
    });
    fireEvent.change(screen.getByLabelText(/Observación/), {
      target: { value: "Se aprobó el monto verificado." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar decisión" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: "250",
        approvedCurrency: "ARS",
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
          approvedCurrency: null, approvedExchangeRate: null, approvedQuoteRequestedDate: null,
          approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
          approvedQuoteProviderTimestamp: null, approvedCalculationVersion: null, rejectedCurrency: "ARS",
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
          source: "CUSTOMER_SUBMISSION",
        });
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");

    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));
    fireEvent.change(screen.getByLabelText(/Observación/), {
      target: { value: "Comprobante borroso" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Rechazar total" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: "0",
        approvedCurrency: "ARS",
        adminObservation: "Comprobante borroso",
      });
    });
    expect(await screen.findByText("No hay comprobantes pendientes de revisión.")).toBeInTheDocument();
  });

  it("muestra preview de imagen cuando el comprobante viene como URL remota", async () => {    server.use(
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

describe("PendingReviewPage simplified review", () => {
  function serveSubmission(submission: ReturnType<typeof makePendingSubmission>) {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([submission]),
      ),
    );
  }

  it("hides the equivalence line when payment and trip share a currency", async () => {
    serveSubmission(makePendingSubmission());

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    expect(screen.queryByText(/Equivale a/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));
    expect(await screen.findByLabelText("Monto a imputar")).toBeInTheDocument();
    expect(screen.queryByText(/Equivale a/)).not.toBeInTheDocument();
  });

  it("shows a compact equivalence line for cross-currency payments", async () => {
    serveSubmission(
      makePendingSubmission("168.83", {
        paymentCurrency: "USD",
        tripCurrency: "ARS",
        amountInTripCurrency: "202596.00",
        exchangeRate: "1200.00",
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();

    const equivalence = await screen.findByText(/→/);
    expect(equivalence).toHaveTextContent("TC");
    expect(equivalence.textContent).not.toMatch(/Equivale a|del viaje/);
  });

  it("shows the collapsed card as grouped hierarchy without label grids", async () => {
    serveSubmission(makePendingSubmission());

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();

    expect(screen.getByText("Bariloche · Cuotas #4, #5")).toBeInTheDocument();
    expect(screen.getByText("Monto informado")).toBeInTheDocument();
    expect(screen.queryByText("Imputación prevista")).not.toBeInTheDocument();
    expect(screen.queryByText("Monto informado por el cliente")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Aprobar monto informado" })).toHaveTextContent("Aprobar");
    expect(screen.getByRole("button", { name: "Revisar monto" })).toBeInTheDocument();
  });

  it("does not repeat the reported-amount label in the expanded neutral state", async () => {
    serveSubmission(makePendingSubmission("240.00"));

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    expect(await screen.findByText("Sin corrección")).toBeInTheDocument();
    expect(screen.queryAllByText(/Monto informado por el cliente/)).toHaveLength(0);
    // Compact allocation rows instead of nested mini-cards with repeated labels.
    // (Dates render in the review timezone, so match structure — not the date.)
    expect(screen.getByText("Cuota #4")).toBeInTheDocument();
    expect(screen.getByText("Cuota #5")).toBeInTheDocument();
    expect(screen.getAllByText(/saldo \$\s?200,00 · previsto \$\s?200,00/)).toHaveLength(2);
    expect(screen.queryByText("Saldo previo")).not.toBeInTheDocument();
    expect(screen.queryByText("Monto imputado")).not.toBeInTheDocument();
    // A single decision path: quick approve leaves, Ocultar + Guardar remain.
    expect(screen.queryByRole("button", { name: "Aprobar monto informado" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ocultar" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeInTheDocument();
  });
});

describe("PendingReviewPage amount slider", () => {
  async function expandReviewCard(reportedAmount = "240.00") {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission(reportedAmount)]),
      ),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    const amountInput = screen.getByLabelText("Monto a imputar");
    const slider = screen.getByRole("slider", { name: "Corregir monto con barra deslizante" });
    const saveButton = screen.getByRole("button", { name: "Guardar decisión" });
    return { amountInput: amountInput as HTMLInputElement, slider: slider as HTMLInputElement, saveButton };
  }

  it("starts centered with the reported amount and a neutral state", async () => {
    const { amountInput, slider, saveButton } = await expandReviewCard();

    expect(amountInput).toHaveValue("240.00");
    expect(slider).toHaveValue("500");
    expect(screen.getByText("Sin corrección")).toBeInTheDocument();
    expect(screen.getByText("Informado")).toBeInTheDocument();
    expect(slider.getAttribute("aria-valuetext")).toContain("Sin corrección");
    // Observation stays optional while the amount matches the reported one.
    expect(screen.getByLabelText("Observación · opcional")).toBeInTheDocument();
    expect(saveButton).not.toBeDisabled();
    expect(screen.queryByRole("button", { name: "Restablecer al monto informado" })).not.toBeInTheDocument();
  });

  it("moving the slider up syncs the input with an optional observation", async () => {
    let reviewRequests = 0;
    server.use(
      http.patch("http://localhost:30002/api/v1/payments/91/review", () => {
        reviewRequests += 1;
        return HttpResponse.json({});
      }),
    );
    const { amountInput, slider, saveButton } = await expandReviewCard();

    fireEvent.change(slider, { target: { value: "625" } });

    expect(amountInput).toHaveValue("300.00");
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();
    expect(screen.getByText(/\+.*respecto de lo informado/)).toBeInTheDocument();
    expect(slider.getAttribute("aria-valuetext")).toContain("Corrección al alza");
    expect(screen.getByLabelText("Observación · opcional")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restablecer al monto informado" })).toBeInTheDocument();
    expect(saveButton).toBeEnabled();
    expect(reviewRequests).toBe(0);
  });

  it("moving the slider down marks a downward correction", async () => {
    const { amountInput, slider, saveButton } = await expandReviewCard();

    fireEvent.change(slider, { target: { value: "375" } });

    expect(amountInput).toHaveValue("180.00");
    expect(await screen.findByText("Corrección a la baja")).toBeInTheDocument();
    expect(screen.getByText(/-.*respecto de lo informado/)).toBeInTheDocument();
    expect(screen.getByLabelText("Observación · opcional")).toBeInTheDocument();
    expect(saveButton).toBeEnabled();
  });

  it("returning the slider to the center restores the reported amount exactly", async () => {
    const { amountInput, slider, saveButton } = await expandReviewCard();

    fireEvent.change(slider, { target: { value: "625" } });
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();

    fireEvent.change(slider, { target: { value: "500" } });

    expect(amountInput).toHaveValue("240.00");
    expect(await screen.findByText("Sin corrección")).toBeInTheDocument();
    expect(screen.getByLabelText("Observación · opcional")).toBeInTheDocument();
    expect(saveButton).not.toBeDisabled();
    expect(screen.queryByRole("button", { name: "Restablecer al monto informado" })).not.toBeInTheDocument();
  });

  it("manual edits move the slider to the matching side", async () => {
    const { amountInput, slider } = await expandReviewCard();

    fireEvent.change(amountInput, { target: { value: "300" } });
    expect(slider).toHaveValue("625");

    fireEvent.change(amountInput, { target: { value: "180" } });
    expect(slider).toHaveValue("375");
  });

  it("keeps manual amounts beyond the initial range valid and the slider coherent", async () => {
    const { amountInput, slider, saveButton } = await expandReviewCard();

    fireEvent.change(amountInput, { target: { value: "700" } });

    expect(amountInput).toHaveValue("700");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();
    // The visual upper bound grows to include 700 instead of desyncing.
    expect(slider).toHaveValue("1000");

    fireEvent.change(screen.getByLabelText(/Observación/), {
      target: { value: "Acreditó más por un pago agrupado." },
    });
    expect(saveButton).not.toBeDisabled();

    // Sliding back recomputes under the extended range instead of snapping.
    fireEvent.change(slider, { target: { value: "999" } });
    expect(amountInput).toHaveValue("699.08");
    expect(slider).toHaveValue("999");
  });

  it("never lets the slider exceed MAX_MONEY", async () => {
    const { amountInput, slider } = await expandReviewCard("99999999.99");

    expect(amountInput).toHaveValue("99999999.99");
    expect(slider).toHaveValue("500");

    fireEvent.change(slider, { target: { value: "1000" } });
    expect(amountInput).toHaveValue("99999999.99");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    fireEvent.change(slider, { target: { value: "0" } });
    expect(amountInput).toHaveValue("0.00");
  });

  it("sends the canonical slider amount in the review payload", async () => {
    let decisionBody: unknown = null;
    let pendingItems = [makePendingSubmission("240.00")];
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json(pendingItems)),
      http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
        decisionBody = await request.json();
        pendingItems = [];
        return HttpResponse.json(approvedSubmissionResponse({ approvedAmount: "300.00" }));
      }),
    );

    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    expect(await screen.findByText("Slavkis, Jose")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));

    fireEvent.change(screen.getByRole("slider", { name: "Corregir monto con barra deslizante" }), {
      target: { value: "625" },
    });
    fireEvent.change(screen.getByLabelText(/Observación/), {
      target: { value: "El banco acreditó más de lo informado." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar decisión" }));

    await waitFor(() => {
      expect(decisionBody).toEqual({
        approvedAmount: "300.00",
        approvedCurrency: "ARS",
        adminObservation: "El banco acreditó más de lo informado.",
      });
    });
  });

  it("reset restores the reported amount without erasing the observation", async () => {
    const { amountInput, slider, saveButton } = await expandReviewCard();

    fireEvent.change(slider, { target: { value: "625" } });
    fireEvent.change(screen.getByLabelText(/Observación/), {
      target: { value: "Borrador que debe conservarse." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Restablecer al monto informado" }));

    expect(amountInput).toHaveValue("240.00");
    expect(slider).toHaveValue("500");
    expect(await screen.findByText("Sin corrección")).toBeInTheDocument();
    expect(screen.getByLabelText("Observación · opcional")).toHaveValue("Borrador que debe conservarse.");
    expect(saveButton).not.toBeDisabled();
  });
});

describe("PendingReviewPage optional observations and local errors", () => {
  it.each([
    ["ARS", "ARS", "250", ""],
    ["ARS", "ARS", "500", "   "],
    ["ARS", "USD", "150", ""],
    ["USD", "ARS", "150", ""],
    ["ARS", "USD", "400.00", ""],
    ["USD", "ARS", "400.00", ""],
    ["ARS", "ARS", "250", "  Confirmed credit  "],
    ["ARS", "ARS", "250", "x".repeat(500)],
  ] as const)("saves %s to %s amount %s with optional note %j", async (original, currency, amount, note) => {
    let body: unknown;
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () =>
        HttpResponse.json([makePendingSubmission("400.00", { paymentCurrency: original, tripCurrency: original })])),
      http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(approvedSubmissionResponse({ approvedAmount: amount, approvedCurrency: currency }));
      }),
    );
    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    await screen.findByText("Slavkis, Jose");
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));
    if (original !== currency) {
      fireEvent.change(screen.getByLabelText("Moneda a imputar"), { target: { value: currency } });
      expect(screen.getByLabelText("Monto a imputar")).toHaveValue("");
    }
    fireEvent.change(screen.getByLabelText("Monto a imputar"), { target: { value: amount } });
    const observation = screen.getByLabelText("Observación · opcional");
    expect(observation).toHaveAttribute("placeholder", "Opcional");
    fireEvent.change(observation, { target: { value: note } });
    const save = screen.getByRole("button", { name: "Guardar decisión" });
    expect(save).toBeEnabled();
    fireEvent.click(save);
    await waitFor(() => expect(body).toEqual({
      approvedAmount: amount, approvedCurrency: currency,
      ...(note.trim() ? { adminObservation: note.trim() } : {}),
    }));
  });

  it.each([400, 409, 500])("places HTTP %s failure in its own panel and reveals only a failed quick approval", async (status) => {
    server.use(
      http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json([
        makePendingSubmission(), makePendingSubmission("240.00", { submissionId: 92, userName: "Ana" }),
      ])),
      http.patch("http://localhost:30002/api/v1/payments/91/review", () =>
        HttpResponse.text(status === 400 ? "El monto aprobado no puede ser negativo" : status === 409
          ? "Este pago ya fue revisado" : "FIN-001: IllegalStateException reportedAmount SQL Hibernate", { status })),
    );
    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    const first = (await screen.findByText("Slavkis, Jose")).closest("article")!;
    const second = screen.getByText("Slavkis, Ana").closest("article")!;
    if (status === 400) {
      fireEvent.click(within(first).getByRole("button", { name: "Revisar monto" }));
      fireEvent.change(within(first).getByLabelText("Monto a imputar"), { target: { value: "250" } });
      fireEvent.change(within(first).getByLabelText(/Observación/), { target: { value: "Keep draft" } });
      fireEvent.click(within(first).getByRole("button", { name: "Guardar decisión" }));
    } else {
      fireEvent.click(within(first).getByRole("button", { name: "Aprobar monto informado" }));
    }
    const alert = await within(first).findByRole("alert");
    const panel = within(first).getByRole("button", { name: "Guardar decisión" }).parentElement!.parentElement!;
    expect(panel).toContainElement(alert);
    expect(alert).toHaveTextContent(status === 400 ? "El monto aprobado no puede ser negativo" : status === 409
      ? "Este pago ya fue revisado" : "Error interno del servidor. Intente nuevamente más tarde.");
    expect(alert).not.toHaveTextContent(/FIN-001|IllegalStateException|reportedAmount|SQL|Hibernate/);
    expect(within(second).queryByLabelText("Monto a imputar")).not.toBeInTheDocument();
    expect(within(second).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(first).getByLabelText("Monto a imputar")).toHaveValue(status === 400 ? "250" : "400.00");
    if (status === 400) expect(within(first).getByLabelText(/Observación/)).toHaveValue("Keep draft");
  });

  it.each(["amount", "slider", "currency", "reset", "observation", "submission"])(
    "clears only the edited card error on %s and retains the other card draft/error", async (edit) => {
      let release!: () => void;
      let retryStarted = false;
      const gate = new Promise<void>((resolve) => { release = resolve; });
      server.use(
        http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json([
          makePendingSubmission(), makePendingSubmission("240.00", { submissionId: 92, userName: "Ana" }),
        ])),
        http.patch("http://localhost:30002/api/v1/payments/:id/review", async () => {
          if (retryStarted) await gate;
          return HttpResponse.text("El monto aprobado supera el saldo pendiente disponible.", { status: 409 });
        }),
      );
      renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
      const first = (await screen.findByText("Slavkis, Jose")).closest("article")!;
      const second = screen.getByText("Slavkis, Ana").closest("article")!;
      for (const card of [first, second]) {
        fireEvent.click(within(card).getByRole("button", { name: "Revisar monto" }));
        fireEvent.change(within(card).getByLabelText("Monto a imputar"), { target: { value: "250" } });
        fireEvent.change(within(card).getByLabelText(/Observación/), { target: { value: "Keep draft" } });
        fireEvent.click(within(card).getByRole("button", { name: "Guardar decisión" }));
        await within(card).findByRole("alert");
        await waitFor(() => expect(within(card).getByRole("button", { name: "Guardar decisión" })).toBeEnabled());
      }
      try {
        if (edit === "amount") fireEvent.change(within(first).getByLabelText("Monto a imputar"), { target: { value: "300" } });
        if (edit === "slider") fireEvent.change(within(first).getByRole("slider"), { target: { value: "625" } });
        if (edit === "currency") fireEvent.change(within(first).getByLabelText("Moneda a imputar"), { target: { value: "USD" } });
        if (edit === "reset") fireEvent.click(within(first).getByRole("button", { name: "Restablecer al monto informado" }));
        if (edit === "observation") fireEvent.change(within(first).getByLabelText(/Observación/), { target: { value: "New draft" } });
        if (edit === "submission") {
          retryStarted = true;
          fireEvent.click(within(first).getByRole("button", { name: "Guardar decisión" }));
        }
        expect(within(first).queryByText("El monto aprobado supera el saldo pendiente disponible.")).not.toBeInTheDocument();
        expect(within(second).getByRole("alert")).toHaveTextContent("El monto aprobado supera el saldo pendiente disponible.");
        expect(within(second).getByLabelText("Monto a imputar")).toHaveValue("250");
        expect(within(second).getByLabelText(/Observación/)).toHaveValue("Keep draft");
      } finally {
        release();
      }
      if (retryStarted) await within(first).findByRole("alert");
    },
  );
});

describe("PendingReviewPage independent administrative currency", () => {
  async function openReview(originalCurrency: "ARS" | "USD" = "ARS", tripCurrency = originalCurrency) {
    server.use(http.get("http://localhost:30002/api/v1/payments/pending-review", () => HttpResponse.json([
      makePendingSubmission("240.00", { paymentCurrency: originalCurrency, tripCurrency, reportedPaymentDate: "2026-09-03" }),
    ])));
    renderWithProviders(<PendingReviewPage />, "ROLE_ADMIN");
    await screen.findByText("Slavkis, Jose");
    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));
    return {
      currency: screen.getByRole("combobox", { name: "Moneda a imputar" }),
      get amount() { return screen.getByLabelText("Monto a imputar"); },
      save: screen.getByRole("button", { name: "Guardar decisión" }),
    };
  }

  it.each([ ["ARS", "USD"], ["USD", "ARS"] ] as const)(
    "clears the amount from %s to %s and on returning, without unlike-currency controls",
    async (original, administrative) => {
      const controls = await openReview(original, administrative);
      expect(controls.currency).toHaveValue(original);
      expect(controls.amount).toHaveValue("240.00");
      expect(screen.getByRole("slider")).toHaveValue("500");
      fireEvent.change(controls.amount, { target: { value: "700" } });
      fireEvent.change(controls.currency, { target: { value: administrative } });
      expect(controls.amount).toHaveValue("");
      expect(controls.save).toBeDisabled();
      expect(screen.queryByRole("slider")).not.toBeInTheDocument();
      expect(screen.getByText("Corrección en otra moneda")).toBeInTheDocument();
      expect(screen.queryByText(/respecto de lo informado/)).not.toBeInTheDocument();
      expect(screen.queryByText(/Corrección al alza|Corrección a la baja/)).not.toBeInTheDocument();
      expect(screen.getByText(`Este monto se imputará directamente en ${administrative}.`)).toBeInTheDocument();
      fireEvent.change(controls.amount, { target: { value: "150" } });
      fireEvent.change(controls.currency, { target: { value: administrative } });
      expect(controls.amount).toHaveValue("150"); // Selecting the current currency is not a currency change.
      fireEvent.change(controls.currency, { target: { value: original } });
      expect(controls.amount).toHaveValue("");
      expect(controls.save).toBeDisabled();
      expect(screen.getByRole("slider")).toHaveValue("500");
      fireEvent.change(controls.amount, { target: { value: "300" } });
      expect(screen.getByRole("slider")).toHaveValue("625"); // Prior 700 extension was discarded.
    },
  );

  it("allows equal digits in another currency and sends the explicit trimmed decision", async () => {
    let body: unknown;
    let calculations = 0;
    server.use(
      http.post("http://localhost:30002/api/v1/payments/calculation", () => { calculations += 1; return HttpResponse.json({}); }),
      http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(approvedSubmissionResponse({ approvedCurrency: "USD", approvedAmount: "240.00" }));
      }),
    );
    const { currency, save } = await openReview("ARS", "USD");
    fireEvent.change(currency, { target: { value: "USD" } });
    fireEvent.change(screen.getByLabelText("Monto a imputar"), { target: { value: "240.00" } });
    expect(screen.getByLabelText("Observación · opcional")).toBeInTheDocument();
    expect(save).toBeEnabled();
    fireEvent.change(screen.getByLabelText(/Observación/), { target: { value: "   " } });
    expect(save).toBeEnabled();
    fireEvent.change(screen.getByLabelText(/Observación/), { target: { value: "  Confirmed USD credit  " } });
    fireEvent.click(save);
    await waitFor(() => expect(body).toEqual({ approvedAmount: "240.00", approvedCurrency: "USD", adminObservation: "Confirmed USD credit" }));
    expect(calculations).toBe(0);
  });

  it("describes conversion using the historical payment date, without estimating or looking up FX", async () => {
    const { currency } = await openReview("USD", "USD");
    fireEvent.change(currency, { target: { value: "ARS" } });
    expect(screen.getByText("Este monto se convertirá a USD usando la cotización correspondiente a la fecha de pago: 03/09/2026.")).toBeInTheDocument();
    expect(screen.queryByRole("slider")).not.toBeInTheDocument();
  });

  it("quick approval after hiding a changed draft still sends the original USD amount and currency", async () => {
    let body: unknown;
    server.use(http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(approvedSubmissionResponse({ paymentCurrency: "USD", approvedCurrency: "USD", tripCurrency: "USD" }));
    }));
    const { currency } = await openReview("USD", "USD");
    currency.focus();
    expect(currency).toHaveFocus();
    fireEvent.change(currency, { target: { value: "ARS" } });
    fireEvent.click(screen.getByRole("button", { name: "Ocultar" }));
    fireEvent.click(screen.getByRole("button", { name: "Aprobar monto informado" }));
    await waitFor(() => expect(body).toEqual({ approvedAmount: "240.00", approvedCurrency: "USD" }));
  });

  it("reset restores original currency, money and slider without erasing the observation", async () => {
    const { currency, amount, save } = await openReview();
    fireEvent.change(amount, { target: { value: "700" } });
    fireEvent.change(currency, { target: { value: "USD" } });
    fireEvent.change(screen.getByLabelText(/Observación/), { target: { value: "Keep this draft" } });
    fireEvent.click(screen.getByRole("button", { name: "Restablecer al monto informado" }));
    expect(currency).toHaveValue("ARS");
    expect(screen.getByLabelText("Monto a imputar")).toHaveValue("240.00");
    expect(screen.getByRole("slider")).toHaveValue("500");
    expect(screen.getByText("Sin corrección")).toBeInTheDocument();
    expect(screen.getByLabelText("Observación · opcional")).toHaveValue("Keep this draft");
    expect(save).not.toBeDisabled();
    fireEvent.change(currency, { target: { value: "USD" } });
    fireEvent.change(currency, { target: { value: "ARS" } });
    expect(screen.getByLabelText("Monto a imputar")).toHaveValue("");
    fireEvent.click(screen.getByRole("button", { name: "Restablecer al monto informado" }));
    expect(screen.getByLabelText("Monto a imputar")).toHaveValue("240.00");
    expect(screen.getByRole("slider")).toHaveValue("500");
  });

  it.each(["", "  Receipt rejected  "])("rejects immediately in the original currency after currency change with note %j", async (note) => {
    let body: unknown;
    server.use(http.patch("http://localhost:30002/api/v1/payments/91/review", async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(approvedSubmissionResponse({ status: "REJECTED", approvedAmount: "0.00", approvedCurrency: null, rejectedCurrency: "ARS" }));
    }));
    const { currency } = await openReview();
    fireEvent.change(currency, { target: { value: "USD" } });
    fireEvent.change(screen.getByLabelText(/Observación/), { target: { value: note } });
    fireEvent.click(screen.getByRole("button", { name: "Rechazar total" }));
    await waitFor(() => expect(body).toEqual({ approvedAmount: "0", approvedCurrency: "ARS", ...(note.trim() ? { adminObservation: note.trim() } : {}) }));
  });

  it("prevents duplicate decisions while pending and preserves the draft after a safe failure", async () => {
    let requests = 0;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(http.patch("http://localhost:30002/api/v1/payments/91/review", async () => {
      requests += 1;
      await gate;
      return HttpResponse.text("El monto aprobado supera el saldo pendiente disponible.", { status: 409 });
    }));
    const { currency, save } = await openReview("ARS", "USD");
    fireEvent.change(currency, { target: { value: "USD" } });
    fireEvent.change(screen.getByLabelText("Monto a imputar"), { target: { value: "150.00" } });
    fireEvent.change(screen.getByLabelText(/Observación/), { target: { value: "Confirmed USD credit" } });
    try {
      fireEvent.click(save);
      await waitFor(() => expect(requests).toBe(1));
      expect(save).toBeDisabled();
      expect(currency).toBeDisabled();
      expect(screen.getByLabelText("Monto a imputar")).toBeDisabled();
      fireEvent.click(save);
      fireEvent.click(screen.getByRole("button", { name: "Rechazar total" }));
      expect(requests).toBe(1);
    } finally {
      release();
    }
    expect(await screen.findByText("El monto aprobado supera el saldo pendiente disponible.")).toBeInTheDocument();
    expect(currency).toHaveValue("USD");
    expect(screen.getByLabelText("Monto a imputar")).toHaveValue("150.00");
    expect(save).not.toBeDisabled();
  });
});
