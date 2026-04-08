import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { UserDashboardPage } from "@/features/users/pages/UserDashboardPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

const INSTALLMENTS_URL = "http://localhost:30002/api/v1/payments/my/installments";
const PREVIEW_URL = "http://localhost:30002/api/v1/payments/preview";
const BANK_ACCOUNTS_URL = "http://localhost:30002/api/v1/bank-accounts";
const PAYMENTS_URL = "http://localhost:30002/api/v1/payments";

const makeInstallment = (overrides: Record<string, unknown> = {}) => ({
  tripId: 77,
  tripName: "Mendoza 2026",
  studentId: 501,
  studentName: "Martina Slavkis",
  studentDni: "45678901",
  installmentId: 101,
  installmentNumber: 1,
  dueDate: "2026-03-25",
  totalDue: 200,
  paidAmount: 0,
  yellowWarningDays: 5,
  tripCurrency: "ARS",
  installmentStatus: "YELLOW",
  latestReceiptStatus: null,
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

const usdBankAccount = {
  id: 2,
  bankName: "ICBC",
  accountLabel: "Cuenta en dólares",
  accountHolder: "Proyecto VA SRL",
  accountNumber: "123-USD",
  taxId: "20-123",
  cbu: "456-USD",
  alias: "ICBC.USD",
  currency: "USD",
  active: true,
  displayOrder: 2,
};

describe("UserDashboardPage", () => {
  it("muestra estados y permite enviar un comprobante con monto libre", async () => {
    let previewPayload: Record<string, unknown> | null = null;
    let paymentPayload: Record<string, FormDataEntryValue> | null = null;

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 101,
            installmentNumber: 1,
            uiStatusCode: "UNDER_REVIEW",
            uiStatusLabel: "En revisión",
            uiStatusTone: "yellow",
            latestReceiptStatus: "PENDING",
          }),
          makeInstallment({
            installmentId: 102,
            installmentNumber: 2,
            dueDate: "2026-04-25",
            uiStatusCode: "RECEIPT_REJECTED",
            uiStatusLabel: "Comprobante rechazado",
            uiStatusTone: "red",
            latestReceiptStatus: "REJECTED",
            latestReceiptObservation: "El comprobante está borroso.",
          }),
          makeInstallment({
            installmentId: 103,
            installmentNumber: 3,
            dueDate: "2026-05-25",
            uiStatusCode: "UP_TO_DATE",
            uiStatusLabel: "Al día",
            uiStatusTone: "green",
            paidAmount: 50,
          }),
          makeInstallment({
            tripId: 88,
            tripName: "Bariloche 2026",
            studentId: 502,
            studentName: "Bruno Slavkis",
            studentDni: "45678902",
            installmentId: 201,
            installmentNumber: 1,
            dueDate: "2026-06-25",
            uiStatusCode: "DUE_SOON",
            uiStatusLabel: "Vence pronto",
            uiStatusTone: "yellow",
          }),
          makeInstallment({
            tripId: 88,
            tripName: "Bariloche 2026",
            studentId: 502,
            studentName: "Bruno Slavkis",
            studentDni: "45678902",
            installmentId: 202,
            installmentNumber: 2,
            dueDate: "2026-07-25",
            uiStatusCode: "UP_TO_DATE",
            uiStatusLabel: "Al día",
            uiStatusTone: "green",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(PREVIEW_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        previewPayload = body;

        return HttpResponse.json({
          anchorInstallmentId: body.anchorInstallmentId,
          tripCurrency: "ARS",
          paymentCurrency: "USD",
          reportedAmount: Number(body.reportedAmount),
          maxAllowedAmount: 400,
          exchangeRate: null,
          totalPendingAmountInTripCurrency: 400,
          amountInTripCurrency: Number(body.reportedAmount),
          reportedPaymentDate: body.reportedPaymentDate,
          installments: [
            {
              receiptId: null,
              installmentId: 201,
              installmentNumber: 1,
              dueDate: "2026-06-25",
              totalDue: 200,
              paidAmount: 0,
              remainingAmount: 200,
              reportedAmount: 200,
              amountInTripCurrency: 200,
              status: null,
            },
            {
              receiptId: null,
              installmentId: 202,
              installmentNumber: 2,
              dueDate: "2026-07-25",
              totalDue: 200,
              paidAmount: 0,
              remainingAmount: 200,
              reportedAmount: 150,
              amountInTripCurrency: 150,
              status: null,
            },
          ],
        });
      }),
      http.post(PAYMENTS_URL, async ({ request }) => {
        const formData = await request.formData();
        paymentPayload = Object.fromEntries(formData.entries());

        return HttpResponse.json(
          {
            submissionId: 999,
            status: "PENDING",
            reportedAmount: 350,
            approvedAmount: 0,
            rejectedAmount: 0,
            paymentCurrency: "USD",
            exchangeRate: null,
            amountInTripCurrency: 350,
            approvedAmountInTripCurrency: 0,
            reportedPaymentDate: "2026-03-31",
            paymentMethod: "BANK_TRANSFER",
            fileKey: "",
            adminObservation: null,
            bankAccountId: 2,
            bankAccountDisplayName: "ICBC - Cuenta en dólares",
            bankAccountAlias: "ICBC.USD",
            tripId: 88,
            tripName: "Bariloche 2026",
            tripCurrency: "ARS",
            studentId: 502,
            studentName: "Bruno Slavkis",
            studentDni: "45678902",
            installments: [
              {
                receiptId: null,
                installmentId: 201,
                installmentNumber: 1,
                dueDate: "2026-06-25",
                totalDue: 200,
                paidAmount: 0,
                remainingAmount: 200,
                reportedAmount: 200,
                amountInTripCurrency: 200,
                status: "PENDING",
              },
              {
                receiptId: null,
                installmentId: 202,
                installmentNumber: 2,
                dueDate: "2026-07-25",
                totalDue: 200,
                paidAmount: 0,
                remainingAmount: 200,
                reportedAmount: 150,
                amountInTripCurrency: 150,
                status: "PENDING",
              },
            ],
          },
          { status: 201 },
        );
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    expect(await screen.findByText("En revisión")).toBeInTheDocument();
    expect(screen.getByText("Comprobante rechazado")).toBeInTheDocument();
    expect(screen.getByText("⚠ El comprobante está borroso.")).toBeInTheDocument();
    expect(
      screen.getByText("Tu comprobante está siendo revisado por el administrador"),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Seleccioná el viaje"), {
      target: { value: "88:502" },
    });
    fireEvent.change(await screen.findByLabelText("Monto a reportar"), {
      target: { value: "350" },
    });

    expect(await screen.findByText("Estado de cuenta")).toBeInTheDocument();

    await waitFor(() =>
      expect(previewPayload).toMatchObject({
        anchorInstallmentId: 201,
        reportedAmount: 350,
        paymentCurrency: "USD",
      }),
    );

    expect(await screen.findByText((text) => text.includes("Se imputa en #1, #2"))).toBeInTheDocument();

    const fileInput = document.querySelector("input[type='file']") as HTMLInputElement;
    const file = new File(["test"], "comprobante.jpg", { type: "image/jpeg" });
    fireEvent.change(fileInput, { target: { files: [file] } });

    const submitBtn = await screen.findByRole("button", { name: "Enviar comprobante" });
    await waitFor(() => expect(submitBtn).not.toBeDisabled());
    fireEvent.click(submitBtn);

    await screen.findByText("¡Comprobante adjuntado!");
    expect(screen.getAllByText("Bariloche 2026 - Bruno Slavkis").length).toBeGreaterThan(0);
    expect(screen.getAllByText("#1, #2").length).toBeGreaterThan(0);
    expect(screen.getByText("comprobante.jpg")).toBeInTheDocument();

    await waitFor(() =>
      expect(paymentPayload).toMatchObject({
        anchorInstallmentId: "201",
        reportedAmount: "350",
        bankAccountId: "2",
      }),
    );
  });

  it("bloquea el envio cuando el grupo tiene comprobantes pendientes de revision", async () => {
    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 101,
            installmentNumber: 1,
            uiStatusCode: "UNDER_REVIEW",
            uiStatusLabel: "En revisión",
            uiStatusTone: "yellow",
            latestReceiptStatus: "PENDING",
          }),
          makeInstallment({
            installmentId: 102,
            installmentNumber: 2,
            dueDate: "2026-04-25",
            latestReceiptStatus: null,
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount])),
    );

    renderWithProviders(<UserDashboardPage />);

    expect(
      await screen.findByText(
        "Esta inscripción tiene comprobantes pendientes de revisión. Hasta que el administrador los revise no podés enviar un nuevo pago.",
      ),
    ).toBeInTheDocument();

    expect(screen.getByLabelText("Monto a reportar")).toBeDisabled();
    expect(screen.getByLabelText("Fecha de pago")).toBeDisabled();
    expect(screen.getByLabelText("Cuenta donde acreditaste el pago")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Enviar comprobante" })).toBeDisabled();
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
