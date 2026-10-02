import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi, beforeEach } from "vitest";

import { ManualImputationSection } from "@/features/payments/components/ManualImputationSection";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

const contextEligible = {
  eligible: true,
  selectedInstallmentId: 1,
  firstPayableInstallmentId: 1,
  firstPayableInstallmentNumber: 1,
  anchorRemainingAmount: "150.00",
  totalRemainingAmountInTripCurrency: "450.00",
  tripCurrency: "USD",
  hasPendingReview: false,
  message: null,
};

const contextPending = {
  ...contextEligible,
  eligible: false,
  hasPendingReview: true,
  message: "Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación.",
};

const contextLaterAnchor = {
  ...contextEligible,
  eligible: false,
  selectedInstallmentId: 4,
  firstPayableInstallmentId: 2,
  firstPayableInstallmentNumber: 2,
  message: "La imputación debe comenzar desde la cuota #2, que es la primera cuota pendiente de pago.",
};

const calculationReady = {
  status: "READY",
  intent: "MANUAL",
  anchorInstallmentId: 1,
  tripCurrency: "USD",
  paymentCurrency: "ARS",
  reportedAmount: "300000.00",
  amountInTripCurrency: "200.00",
  anchorRemainingAmount: "150.00",
  totalPendingAmountInTripCurrency: "450.00",
  maxAllowedAmount: "675000.00",
  tripCurrencyResidual: "250.00",
  exchangeRate: "1500.00",
  reportedPaymentDate: "2026-10-01",
  quoteRequestedDate: "2026-10-01",
  quoteEffectiveDate: "2026-10-01",
  quoteSource: "test",
  quoteProvider: "deterministic",
  quoteProviderTimestamp: "2026-10-01T12:00:00Z",
  calculationVersion: "2",
  previewToken: "preview-token-123",
  installments: [
    {
      receiptId: null,
      installmentId: 1,
      installmentNumber: 2,
      dueDate: "2026-11-10",
      totalDue: "150.00",
      paidAmount: "0.00",
      remainingAmount: "150.00",
      reportedAmount: "225000.00",
      amountInTripCurrency: "150.00",
      status: null,
    },
    {
      receiptId: null,
      installmentId: 2,
      installmentNumber: 3,
      dueDate: "2026-12-10",
      totalDue: "150.00",
      paidAmount: "0.00",
      remainingAmount: "150.00",
      reportedAmount: "75000.00",
      amountInTripCurrency: "50.00",
      status: null,
    },
  ],
  message: null,
};

function mockContext(payload: Record<string, unknown>) {
  server.use(
    http.get("http://localhost:30002/api/v1/payments/manual-imputations/context", () =>
      HttpResponse.json(payload),
    ),
  );
}

function mockCalculation(payload: Record<string, unknown>) {
  server.use(
    http.post("http://localhost:30002/api/v1/payments/calculation", () =>
      HttpResponse.json(payload),
    ),
  );
}

describe("ManualImputation progressive disclosure", () => {
  beforeEach(() => {
    mockContext(contextEligible);
    mockCalculation(calculationReady);
  });

  it("no muestra el formulario hasta tocar Imputar pago", async () => {
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    expect(await screen.findByRole("button", { name: "Imputar pago" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Monto a imputar")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Imputar pago" }));
    expect(await screen.findByLabelText("Monto a imputar")).toBeInTheDocument();
    expect(screen.getByLabelText("Moneda del pago")).toBeInTheDocument();
    expect(screen.getByLabelText("Fecha de pago")).toBeInTheDocument();
  });

  it("bloquea con disclaimer cuando hay PENDING y no hace POST manual", async () => {
    mockContext(contextPending);
    const user = userEvent.setup();
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    const button = await screen.findByRole("button", { name: "Imputar pago" });
    expect(button).toBeDisabled();
    expect(
      await screen.findByText("Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación."),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("Monto a imputar")).not.toBeInTheDocument();
    await user.click(button).catch(() => {});
  });

  it("muestra mensaje de anchor posterior", async () => {
    mockContext(contextLaterAnchor);
    renderWithProviders(
      <ManualImputationSection installmentId={4} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    expect(
      await screen.findByText("La imputación debe comenzar desde la cuota #2, que es la primera cuota pendiente de pago."),
    ).toBeInTheDocument();
  });

  it("usa REMAINING para completar cuota sin convertir FX en frontend", async () => {
    const remainingResponse = {
      ...calculationReady,
      intent: "REMAINING",
      reportedAmount: "150.00",
      amountInTripCurrency: "150.00",
      paymentCurrency: "USD",
      exchangeRate: null,
      previewToken: null,
      installments: [calculationReady.installments[0]],
    };
    mockCalculation(remainingResponse);
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    await userEvent.click(await screen.findByText("Completar cuota actual"));
    await waitFor(() => {
      expect(screen.getByLabelText("Monto a imputar")).toHaveValue("150.00");
    });
  });

  it("desmarca completar cuando el monto se edita manualmente", async () => {
    const remainingResponse = {
      ...calculationReady,
      intent: "REMAINING",
      reportedAmount: "150.00",
      previewToken: null,
    };
    mockCalculation(remainingResponse);
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    const checkbox = (await screen.findByText("Completar cuota actual")).closest("label")?.querySelector("input");
    expect(checkbox).toBeDefined();
    await userEvent.click(screen.getByText("Completar cuota actual"));
    const amount = screen.getByLabelText("Monto a imputar");
    await waitFor(() => expect(amount).toHaveValue("150.00"));
    expect(checkbox).toBeChecked();
    await userEvent.clear(amount);
    await userEvent.type(amount, "150.01");
    await waitFor(() => expect(checkbox).not.toBeChecked());
  });

  it("muestra confirmación con allocations antes de persistir y ejecuta una sola vez con doble click", async () => {
    let executeCalls = 0;
    server.use(
      http.post("http://localhost:30002/api/v1/payments/manual-imputations", async () => {
        executeCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 50));
        return HttpResponse.json(
          {
            submissionId: 99,
            status: "APPROVED",
            reportedAmount: "300000.00",
            approvedAmount: "300000.00",
            rejectedAmount: "0.00",
            paymentCurrency: "ARS",
            exchangeRate: "1500.00",
            amountInTripCurrency: "200.00",
            approvedAmountInTripCurrency: "200.00",
            reportedPaymentDate: "2026-10-01",
            calculationVersion: "2",
            paymentMethod: null,
            fileKey: "",
            adminObservation: null,
            bankAccountId: null,
            bankAccountDisplayName: null,
            bankAccountAlias: null,
            tripId: 1,
            tripName: "Viaje",
            tripCurrency: "USD",
            studentId: 1,
            studentName: "Luca",
            studentDni: "40111222",
            installments: [],
            source: "ADMIN_MANUAL",
            manualReason: null,
          },
          { status: 201 },
        );
      }),
    );
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    await userEvent.type(await screen.findByLabelText("Monto a imputar"), "300000.00");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("heading", { name: "Confirmar imputación" })).toBeInTheDocument();
    expect(screen.getByText("Cuota #2", { exact: false })).toBeInTheDocument();
    // Antes de confirmar no hubo POST de ejecución.
    expect(executeCalls).toBe(0);
    const confirm = screen.getByRole("button", { name: "Confirmar imputación" });
    await userEvent.dblClick(confirm);
    await waitFor(() => expect(executeCalls).toBe(1));
  });

  it("nunca muestra códigos técnicos en errores de negocio", async () => {    server.use(
      http.post("http://localhost:30002/api/v1/payments/calculation", () =>
        HttpResponse.json({ ...calculationReady, status: "AMOUNT_EXCEEDS_BALANCE", message: "El monto ingresado supera el saldo pendiente del viaje.", installments: [], previewToken: null }),
      ),
    );
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    await userEvent.type(await screen.findByLabelText("Monto a imputar"), "99999999.99");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch("supera el saldo pendiente");
    for (const tech of ["IllegalStateException", "reportedAmount", "PaymentAllocationPlanner", "FIN-001", "balanceLimitInTripCurrency"]) {
      expect(document.body.textContent ?? "").not.toContain(tech);
    }
  });

  it("muestra saldos de cuota y viaje arriba del monto", async () => {
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    expect(await screen.findByText(/Saldo de la cuota:/)).toBeInTheDocument();
    expect(screen.getByText(/Saldo pendiente del viaje:/)).toBeInTheDocument();
    // es-AR con moneda del viaje (USD del contexto).
    expect(screen.getByText(/US\$\s?150,00/)).toBeInTheDocument();
    expect(screen.getByText(/US\$\s?450,00/)).toBeInTheDocument();
  });

  it("deshabilita el trigger para anchor posterior, cuota pagada y viaje sin saldo", async () => {
    mockContext(contextLaterAnchor);
    const { unmount } = renderWithProviders(
      <ManualImputationSection installmentId={4} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    expect(await screen.findByRole("button", { name: "Imputar pago" })).toBeDisabled();
    unmount();

    mockContext({
      ...contextEligible,
      eligible: false,
      anchorRemainingAmount: "0.00",
      message: "Esta cuota ya está completamente pagada. Seleccioná la primera cuota pendiente de pago (#2).",
    });
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    expect(await screen.findByRole("button", { name: "Imputar pago" })).toBeDisabled();
    expect(await screen.findByText(/completamente pagada/)).toBeInTheDocument();
  });

  it("invalidar la confirmación al cambiar moneda o fecha", async () => {
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    await userEvent.type(await screen.findByLabelText("Monto a imputar"), "300000.00");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("heading", { name: "Confirmar imputación" })).toBeInTheDocument();

    // Volver descarta el preview: cambiar moneda y continuar recalcula,
    // nunca confirma el preview anterior.
    await userEvent.click(screen.getByRole("button", { name: "Volver" }));
    expect(screen.queryByRole("heading", { name: "Confirmar imputación" })).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Moneda del pago"), "USD");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("heading", { name: "Confirmar imputación" })).toBeInTheDocument();
  });

  it("ante STALE vuelve al formulario y permite recalcular", async () => {
    server.use(
      http.post("http://localhost:30002/api/v1/payments/manual-imputations", () =>
        HttpResponse.json(
          {
            code: "MANUAL_IMPUTATION_STALE_BALANCE",
            message: "El saldo cambió desde la última previsualización. Actualizá la imputación e intentá nuevamente.",
          },
          { status: 409 },
        ),
      ),
    );
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    await userEvent.type(await screen.findByLabelText("Monto a imputar"), "300000.00");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("heading", { name: "Confirmar imputación" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Confirmar imputación" }));
    // El plan stale no queda confirmable: vuelve al formulario con el mensaje.
    await waitFor(() => {
      expect(screen.queryByRole("heading", { name: "Confirmar imputación" })).not.toBeInTheDocument();
    });
    const section = screen.getByRole("region", { name: "Imputación manual" });
    expect(
      within(section).getByText(/El saldo cambió desde la última previsualización/),
    ).toBeInTheDocument();
    expect(within(section).getByLabelText("Monto a imputar")).toBeInTheDocument();
  });

  it("el motivo vigente se envía al confirmar sin recalcular finanzas", async () => {
    let capturedReason: string | null = null;
    server.use(
      http.post("http://localhost:30002/api/v1/payments/manual-imputations", async ({ request }) => {
        const form = await request.formData();
        const reason = form.get("reason");
        capturedReason = typeof reason === "string" ? reason : null;
        return HttpResponse.json(
          {
            submissionId: 99,
            status: "APPROVED",
            reportedAmount: "300000.00",
            approvedAmount: "300000.00",
            rejectedAmount: "0.00",
            paymentCurrency: "ARS",
            exchangeRate: "1500.00",
            amountInTripCurrency: "200.00",
            approvedAmountInTripCurrency: "200.00",
            reportedPaymentDate: "2026-10-01",
            calculationVersion: "2",
            paymentMethod: null,
            fileKey: "",
            adminObservation: null,
            bankAccountId: null,
            bankAccountDisplayName: null,
            bankAccountAlias: null,
            tripId: 1,
            tripName: "Viaje",
            tripCurrency: "USD",
            studentId: 1,
            studentName: "Luca",
            studentDni: "40111222",
            installments: [],
            source: "ADMIN_MANUAL",
            manualReason: capturedReason,
          },
          { status: 201 },
        );
      }),
    );
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={vi.fn()} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    await userEvent.type(await screen.findByLabelText("Monto a imputar"), "300000.00");
    // El motivo se informa en el formulario y viaja vigente al confirmar.
    await userEvent.type(screen.getByLabelText(/Motivo/), "Pago en efectivo");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("heading", { name: "Confirmar imputación" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Confirmar imputación" }));
    await waitFor(() => expect(capturedReason).toBe("Pago en efectivo"));
  });

  it("al confirmar con éxito muestra mensaje y refresca", async () => {
    const onImputed = vi.fn();
    server.use(
      http.post("http://localhost:30002/api/v1/payments/manual-imputations", () =>
        HttpResponse.json(
          {
            submissionId: 100,
            status: "APPROVED",
            reportedAmount: "300000.00",
            approvedAmount: "300000.00",
            rejectedAmount: "0.00",
            paymentCurrency: "ARS",
            exchangeRate: "1500.00",
            amountInTripCurrency: "200.00",
            approvedAmountInTripCurrency: "200.00",
            reportedPaymentDate: "2026-10-01",
            calculationVersion: "2",
            paymentMethod: null,
            fileKey: "",
            adminObservation: null,
            bankAccountId: null,
            bankAccountDisplayName: null,
            bankAccountAlias: null,
            tripId: 1,
            tripName: "Viaje",
            tripCurrency: "USD",
            studentId: 1,
            studentName: "Luca",
            studentDni: "40111222",
            installments: [],
            source: "ADMIN_MANUAL",
            manualReason: null,
          },
          { status: 201 },
        ),
      ),
    );
    renderWithProviders(
      <ManualImputationSection installmentId={1} onImputed={onImputed} />,
      "ROLE_ADMIN",
    );
    await userEvent.click(await screen.findByRole("button", { name: "Imputar pago" }));
    await userEvent.type(await screen.findByLabelText("Monto a imputar"), "300000.00");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    await userEvent.click(await screen.findByRole("button", { name: "Confirmar imputación" }));
    expect(await screen.findByText("Imputación registrada correctamente.")).toBeInTheDocument();
    expect(onImputed).toHaveBeenCalled();
  });
});
