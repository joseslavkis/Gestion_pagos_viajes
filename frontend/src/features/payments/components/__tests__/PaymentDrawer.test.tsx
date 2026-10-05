import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { PaymentDrawer } from "@/features/payments/components/PaymentDrawer";
import type { PaymentInstallmentHistoryDTO } from "@/features/payments/types/payments-dtos";
import type { SpreadsheetRowDTO, SpreadsheetRowInstallmentDTO } from "@/features/trips/types/trips-dtos";
import styles from "@/features/trips/pages/SpreadsheetPage.module.css";
import { renderWithProviders } from "@/test/test-utils";

const historyEntry = (overrides: Partial<PaymentInstallmentHistoryDTO> = {}): PaymentInstallmentHistoryDTO => ({
  id: 1,
  submissionId: 91,
  installmentId: 1,
  installmentNumber: 1,
  reportedAmount: "240000.00",
  paymentCurrency: "ARS",
  originalReportedAmount: overrides.reportedAmount ?? "240000.00",
  allocationCurrency: overrides.paymentCurrency ?? "ARS",
  allocationExchangeRate: null,
  allocationQuoteRequestedDate: null,
  allocationQuoteEffectiveDate: null,
  allocationQuoteSource: null,
  allocationQuoteProvider: null,
  allocationQuoteProviderTimestamp: null,
  allocationCalculationVersion: null,
  exchangeRate: "1200",
  amountInTripCurrency: "200.00",
  reportedPaymentDate: "2026-05-10",
  paymentMethod: "BANK_TRANSFER",
  status: "APPROVED",
  fileKey: "",
  adminObservation: null,
  bankAccountId: 1,
  bankAccountDisplayName: "ICBC - pesos",
  bankAccountAlias: "ICBC.PESOS",
  source: "CUSTOMER_SUBMISSION",
  ...overrides,
});

const row: SpreadsheetRowDTO = {
  userId: 10,
  studentId: 20,
  name: "Ana",
  lastname: "Parent",
  phone: "381123123",
  email: "ana@test.com",
  studentLastname: "Perez",
  studentName: "Luca",
  studentDni: "40111222",
  userCompleted: false,
  installments: [],
};

const installment = (totalDue: number): SpreadsheetRowInstallmentDTO => ({
  id: 1,
  installmentNumber: 1,
  dueDate: "2026-05-10",
  capitalAmount: totalDue,
  retroactiveAmount: 0,
  totalDue,
  paidAmount: 0,
  status: "YELLOW",
  uiStatusCode: "UP_TO_DATE",
  uiStatusLabel: "Al día",
  uiStatusTone: "green",
});

let historyResponse: PaymentInstallmentHistoryDTO[] = [];

vi.mock("@/features/payments/services/payments-service", () => ({
  useInstallmentReceipts: () => ({
    get data() {
      return historyResponse;
    },
    isLoading: false,
    error: null,
  }),
  useVoidPayment: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
  }),
}));

vi.mock("@/features/payments/services/manual-imputation-service", () => ({
  useManualImputationContext: () => ({
    data: undefined,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
  }),
  useManualImputation: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
    error: null,
  }),
}));

async function renderDrawer(
  tripCurrency: "ARS" | "USD",
  totalDue: number,
  entry: PaymentInstallmentHistoryDTO,
) {
  historyResponse = [entry];
  return renderWithProviders(
    <PaymentDrawer
      onClose={vi.fn()}
      row={row}
      installment={installment(totalDue)}
      tripCurrency={tripCurrency}
    />,
    "ROLE_ADMIN",
  );
}

/** The label lives in a <span>, so the formatted value sits in the parent row. */
function historyRow(label: RegExp): HTMLElement {
  return screen.getByText(label).closest("div") as HTMLElement;
}

describe("PaymentDrawer", () => {
  it("keeps the original ARS report separate from accredited USD allocations", async () => {
    await renderDrawer("USD", 200, historyEntry({
      originalReportedAmount: "306000.00", reportedAmount: "150.00",
      paymentCurrency: "ARS", allocationCurrency: "USD", amountInTripCurrency: "150.00",
    }));
    expect(historyRow(/Monto reportado:/)).toHaveTextContent("306.000,00");
    expect(historyRow(/Monto reportado:/)).not.toHaveTextContent("US$");
    expect(historyRow(/Monto acreditado asignado:/)).toHaveTextContent("US$");
    expect(historyRow(/Monto acreditado asignado:/)).toHaveTextContent("150,00");
    expect(historyRow(/Equivalente imputado al viaje:/)).toHaveTextContent("150,00");
    expect(screen.queryByText(/Cotización de la acreditación:/)).not.toBeInTheDocument();
  });

  it("retains the approved historical quote on voided ARS credit to a USD trip", async () => {
    await renderDrawer("USD", 200, historyEntry({
      originalReportedAmount: "200.00", paymentCurrency: "USD", exchangeRate: null,
      reportedAmount: "153000.00", allocationCurrency: "ARS", amountInTripCurrency: "100.00",
      allocationExchangeRate: "1530.00000000", allocationQuoteEffectiveDate: "2026-09-02", status: "VOIDED",
    }));
    expect(historyRow(/Monto reportado:/)).toHaveTextContent("US$");
    expect(historyRow(/Monto acreditado asignado:/)).toHaveTextContent("153.000,00");
    expect(historyRow(/Monto acreditado asignado:/)).not.toHaveTextContent("US$");
    expect(historyRow(/Cotización de la acreditación:/)).toHaveTextContent("1530.00000000 ARS por USD");
    expect(historyRow(/Cotización de la acreditación:/)).toHaveTextContent("02/09/2026");
    expect(screen.getByText("Anulado")).toBeInTheDocument();
  });
  it("muestra alumno primero y usa color neutral para Al día", async () => {
    await renderDrawer("ARS", 1000, historyEntry());

    expect(await screen.findByText("Perez, Luca")).toBeInTheDocument();
    expect(screen.getByText("Responsable: Parent, Ana")).toBeInTheDocument();
    expect(screen.getByText("ana@test.com")).toBeInTheDocument();

    const badge = screen.getByText("Al día").parentElement;
    expect(badge).toHaveClass(styles.statusNeutral);
  });

  describe("importe imputado en la moneda del viaje", () => {
    it("formatea el equivalente con la moneda del viaje cuando el pago es ARS y el viaje USD", async () => {
      await renderDrawer(
        "USD",
        2000,
        historyEntry({ paymentCurrency: "ARS", reportedAmount: "240000.00", amountInTripCurrency: "200.00" }),
      );

      await screen.findByText("Perez, Luca");
      // Reported amount keeps the payment currency.
      const reported = historyRow(/Monto reportado:/);
      expect(reported).toHaveTextContent("240.000,00");
      expect(reported).not.toHaveTextContent("US$");
      // The imputed equivalent is always trip currency, never inferred from the rate.
      const equivalent = historyRow(/Equivalente imputado al viaje:/);
      expect(equivalent).toHaveTextContent("US$");
      expect(equivalent).toHaveTextContent("200,00");
    });

    it("formatea el equivalente con la moneda del viaje cuando el pago es USD y el viaje ARS", async () => {
      await renderDrawer(
        "ARS",
        240000,
        historyEntry({ paymentCurrency: "USD", reportedAmount: "200.00", amountInTripCurrency: "240000.00" }),
      );

      await screen.findByText("Perez, Luca");
      const reported = historyRow(/Monto reportado:/);
      expect(reported).toHaveTextContent("200,00");
      const equivalent = historyRow(/Equivalente imputado al viaje:/);
      expect(equivalent).toHaveTextContent("240.000,00");
      expect(equivalent).not.toHaveTextContent("US$");
    });

    it("formatea el equivalente con la misma moneda cuando pago y viaje coinciden", async () => {
      await renderDrawer(
        "ARS",
        300000,
        historyEntry({
          paymentCurrency: "ARS",
          reportedAmount: "300000.00",
          amountInTripCurrency: "300000.00",
          exchangeRate: null,
        }),
      );

      await screen.findByText("Perez, Luca");
      const equivalent = historyRow(/Equivalente imputado al viaje:/);
      expect(equivalent).toHaveTextContent("300.000,00");
      expect(equivalent).not.toHaveTextContent("US$");
    });

    it("usa la moneda del viaje para el total de la cuota, no una moneda fija", async () => {
      await renderDrawer(
        "USD",
        2000,
        historyEntry({ paymentCurrency: "ARS", reportedAmount: "240000.00", amountInTripCurrency: "200.00" }),
      );

      await screen.findByText("Perez, Luca");
      const total = historyRow(/^Total:$/);
      expect(total).toHaveTextContent("US$");
      expect(total).toHaveTextContent("2.000,00");
    });
  });

  describe("imputación manual en historial", () => {
    const manualEntry = () =>
      historyEntry({
        paymentMethod: null,
        adminObservation: "Efectivo",
        bankAccountId: null,
        bankAccountDisplayName: null,
        bankAccountAlias: null,
        source: "ADMIN_MANUAL",
        manualReason: "Efectivo",
      });

    it("muestra tipo y motivo una sola vez, sin fila de cuenta", async () => {
      await renderDrawer("ARS", 1000, manualEntry());

      await screen.findByText("Perez, Luca");
      expect(screen.getByText("Imputación manual")).toBeInTheDocument();
      // Motivo una sola vez aunque la observación traiga el mismo texto.
      expect(screen.getAllByText(/Efectivo/)).toHaveLength(1);
      expect(screen.queryByText("Cuenta acreditada")).not.toBeInTheDocument();
      expect(screen.queryByText("Observación:")).not.toBeInTheDocument();
    });
  });
});
