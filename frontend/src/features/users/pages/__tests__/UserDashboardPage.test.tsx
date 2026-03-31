import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { UserDashboardPage } from "@/features/users/pages/UserDashboardPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

const INSTALLMENTS_URL = "http://localhost:30002/api/v1/payments/my/installments";
const BANK_ACCOUNTS_URL = "http://localhost:30002/api/v1/bank-accounts";
const PAYMENTS_URL = "http://localhost:30002/api/v1/payments";

const makeInstallment = (overrides: Record<string, unknown> = {}) => ({
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
  ...overrides,
});

const bankAccount = {
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
};

describe("UserDashboardPage", () => {
  it("muestra los estados de cuotas y permite enviar comprobante con archivo adjunto", async () => {
    let paymentPayload: Record<string, string> | null = null;

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({ uiStatusCode: "DUE_SOON", uiStatusLabel: "Vence pronto", uiStatusTone: "yellow" }),
          makeInstallment({
            installmentId: 102,
            installmentNumber: 2,
            dueDate: "2026-04-25",
            uiStatusCode: "UNDER_REVIEW",
            uiStatusLabel: "En revisión",
            uiStatusTone: "yellow",
          }),
          makeInstallment({
            installmentId: 103,
            installmentNumber: 3,
            dueDate: "2026-05-25",
            uiStatusCode: "RECEIPT_REJECTED",
            uiStatusLabel: "Comprobante rechazado",
            uiStatusTone: "red",
            latestReceiptObservation: "El comprobante está borroso.",
          }),
          makeInstallment({
            installmentId: 104,
            installmentNumber: 4,
            dueDate: "2026-06-25",
            uiStatusCode: "UP_TO_DATE",
            uiStatusLabel: "Al día",
            uiStatusTone: "green",
            paidAmount: 50,
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount])),
      http.post(PAYMENTS_URL, async ({ request }) => {
        const formData = await request.formData();
        paymentPayload = Object.fromEntries(formData.entries()) as Record<string, string>;
        return HttpResponse.json(
          {
            id: 999,
            installmentId: 101,
            installmentNumber: 1,
            reportedAmount: 200,
            paymentCurrency: "ARS",
            exchangeRate: null,
            amountInTripCurrency: 200,
            reportedPaymentDate: "2026-03-25",
            paymentMethod: "BANK_TRANSFER",
            status: "PENDING",
            fileKey: "",
            adminObservation: null,
            bankAccountId: 1,
            bankAccountDisplayName: "ICBC - Cuenta en pesos",
            bankAccountAlias: "ICBC.PESOS",
          },
          { status: 201 },
        );
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    expect(await screen.findByText("Vence pronto")).toBeInTheDocument();
    expect(screen.getByText("En revisión")).toBeInTheDocument();
    expect(screen.getByText("⚠ El comprobante está borroso.")).toBeInTheDocument();
    expect(
      screen.getByText("Tu comprobante está siendo revisado por el administrador"),
    ).toBeInTheDocument();
    expect(
      screen.getByText((text) => text.includes("Abonado:") && text.includes("Resta:")),
    ).toBeInTheDocument();

    // "Mis hijos" section is no longer on this page
    expect(
      screen.queryByText("Podés reclamar hijos solo si la agencia precargó su DNI en algún viaje."),
    ).not.toBeInTheDocument();

    // "Historial de viajes" section is no longer on this page
    expect(screen.queryByText("Historial de viajes")).not.toBeInTheDocument();

    // Select the trip and fill in the form
    fireEvent.change(screen.getByLabelText("Seleccioná el viaje"), {
      target: { value: "77:501" },
    });
    const amountInput = await screen.findByLabelText("Monto pagado");
    fireEvent.change(amountInput, { target: { value: "200.00" } });

    // Attach a file (mandatory)
    const fileInput = document.querySelector("input[type='file']") as HTMLInputElement;
    const file = new File(["test"], "comprobante.jpg", { type: "image/jpeg" });
    fireEvent.change(fileInput, { target: { files: [file] } });

    // Button is now enabled — submit
    const submitBtn = await screen.findByRole("button", { name: "Enviar comprobante" });
    await waitFor(() => expect(submitBtn).not.toBeDisabled());
    fireEvent.click(submitBtn);

    // Success screen should appear
    await screen.findByText("¡Comprobante adjuntado!");
    expect(screen.getAllByText("Viaje 1 - Martina Slavkis").length).toBeGreaterThan(0);
    expect(screen.getByText("comprobante.jpg")).toBeInTheDocument();

    await waitFor(() =>
      expect(paymentPayload).toMatchObject({
        installmentId: "101",
        reportedAmount: "200",
        bankAccountId: "1",
      }),
    );
  });

  it("bloquea el envio si no se adjunta comprobante (boton deshabilitado)", async () => {
    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([makeInstallment()]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount])),
    );

    renderWithProviders(<UserDashboardPage />);

    // Select trip and fill amount — but do NOT attach a file
    fireEvent.change(await screen.findByLabelText("Seleccioná el viaje"), {
      target: { value: "77:501" },
    });
    const amountInput = await screen.findByLabelText("Monto pagado");
    fireEvent.change(amountInput, { target: { value: "200.00" } });

    // The submit button must be disabled when no file is attached
    const submitBtn = screen.getByRole("button", { name: "Enviar comprobante" });
    expect(submitBtn).toBeDisabled();

    // The folder hint shows the "obligatorio" text
    expect(screen.getByText("Hacé click para adjuntar el comprobante (obligatorio)")).toBeInTheDocument();
  });

  it("muestra la opcion Deposito en el selector de metodo de pago", async () => {
    server.use(
      http.get(INSTALLMENTS_URL, () => HttpResponse.json([])),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([])),
    );

    renderWithProviders(<UserDashboardPage />);

    await screen.findByText("Panel de pagos");

    const paymentMethodSelect = screen.getByLabelText("Método de pago");
    expect(paymentMethodSelect.querySelector("option[value='DEPOSIT']")).not.toBeNull();
    expect(paymentMethodSelect.querySelector("option[value='CARD']")).toBeNull();
  });
});
