import { fireEvent, screen, waitFor } from "@testing-library/react";
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
    ...overrides,
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
    // Correcting the amount requires an observation before saving.
    expect(screen.getByRole("button", { name: "Guardar decisión" })).toBeDisabled();
    expect(reviewRequests).toBe(0);

    fireEvent.change(screen.getByLabelText(/Observación/), {
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

    fireEvent.click(screen.getByRole("button", { name: "Revisar monto" }));
    fireEvent.change(screen.getByLabelText(/Observación/), {
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
    expect(
      screen.getByText("vence 25/03/2026 · saldo $ 200,00 · previsto $ 200,00"),
    ).toBeInTheDocument();
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
    expect(screen.getByLabelText("Observación")).toBeInTheDocument();
    expect(saveButton).not.toBeDisabled();
    expect(screen.queryByRole("button", { name: "Restablecer al monto informado" })).not.toBeInTheDocument();
  });

  it("moving the slider up syncs the input and requires an observation", async () => {
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
    expect(screen.getByLabelText(/Observación · requerida/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restablecer al monto informado" })).toBeInTheDocument();
    expect(saveButton).toBeDisabled();
    expect(reviewRequests).toBe(0);
  });

  it("moving the slider down marks a downward correction", async () => {
    const { amountInput, slider, saveButton } = await expandReviewCard();

    fireEvent.change(slider, { target: { value: "375" } });

    expect(amountInput).toHaveValue("180.00");
    expect(await screen.findByText("Corrección a la baja")).toBeInTheDocument();
    expect(screen.getByText(/-.*respecto de lo informado/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Observación · requerida/)).toBeInTheDocument();
    expect(saveButton).toBeDisabled();
  });

  it("returning the slider to the center restores the reported amount exactly", async () => {
    const { amountInput, slider, saveButton } = await expandReviewCard();

    fireEvent.change(slider, { target: { value: "625" } });
    expect(await screen.findByText("Corrección al alza")).toBeInTheDocument();

    fireEvent.change(slider, { target: { value: "500" } });

    expect(amountInput).toHaveValue("240.00");
    expect(await screen.findByText("Sin corrección")).toBeInTheDocument();
    expect(screen.getByLabelText("Observación")).toBeInTheDocument();
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
    expect(screen.getByLabelText("Observación")).toHaveValue("Borrador que debe conservarse.");
    expect(saveButton).not.toBeDisabled();
  });
});
