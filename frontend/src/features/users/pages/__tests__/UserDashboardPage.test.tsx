import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { UserDashboardPage } from "@/features/users/pages/UserDashboardPage";
import { PaymentSubmissionDTOSchema } from "@/features/payments/types/payments-dtos";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

const INSTALLMENTS_URL = "http://localhost:30002/api/v1/payments/my/installments";
const CALCULATION_URL = "http://localhost:30002/api/v1/payments/calculation";
const BANK_ACCOUNTS_URL = "http://localhost:30002/api/v1/bank-accounts";
const PAYMENTS_URL = "http://localhost:30002/api/v1/payments";

const decimal = (value: string | number) => String(value);

function makeCalculationResponse({
  body,
  tripCurrency,
  remainingAmount,
  reportedAmount,
  amountInTripCurrency,
  maxAllowedAmount = reportedAmount,
  exchangeRate = null,
  installments = [],
  status = "READY",
  message = null,
}: {
  body: Record<string, unknown>;
  tripCurrency: "ARS" | "USD";
  remainingAmount: string | number;
  reportedAmount: string | number | null;
  amountInTripCurrency: string | number | null;
  maxAllowedAmount?: string | number | null;
  exchangeRate?: string | number | null;
  installments?: Record<string, unknown>[];
  status?: "READY" | "AMOUNT_EXCEEDS_BALANCE" | "UNPAYABLE" | "QUOTE_UNAVAILABLE" | "EXPIRED";
  message?: string | null;
}) {
  return {
    status,
    intent: body.intent,
    anchorInstallmentId: body.anchorInstallmentId,
    tripCurrency,
    paymentCurrency: body.paymentCurrency,
    reportedAmount: reportedAmount == null ? null : decimal(reportedAmount),
    amountInTripCurrency: amountInTripCurrency == null ? null : decimal(amountInTripCurrency),
    remainingAmount: decimal(remainingAmount),
    maxAllowedAmount: maxAllowedAmount == null ? null : decimal(maxAllowedAmount),
    tripCurrencyResidual: "0.00",
    exchangeRate: exchangeRate == null ? null : decimal(exchangeRate),
    reportedPaymentDate: body.reportedPaymentDate,
    previewToken: status === "READY" ? "preview-token" : null,
    installments,
    message,
  };
}

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
    let calculationPayload: Record<string, unknown> | null = null;
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
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        calculationPayload = body;
        const reportedAmount = String(body.reportedAmount ?? 200);

        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "ARS",
          remainingAmount: "400.00",
          reportedAmount,
          amountInTripCurrency: reportedAmount,
          maxAllowedAmount: "400.00",
          installments: [
            {
              receiptId: null,
              installmentId: 201,
              installmentNumber: 1,
              dueDate: "2026-06-25",
              totalDue: "200.00",
              paidAmount: "0.00",
              remainingAmount: "200.00",
              reportedAmount: "200.00",
              amountInTripCurrency: "200.00",
              status: null,
            },
            {
              receiptId: null,
              installmentId: 202,
              installmentNumber: 2,
              dueDate: "2026-07-25",
              totalDue: "200.00",
              paidAmount: "0.00",
              remainingAmount: "200.00",
              reportedAmount: decimal(Number(reportedAmount) - 200),
              amountInTripCurrency: decimal(Number(reportedAmount) - 200),
              status: null,
            },
          ],
        }));
      }),
      http.post(PAYMENTS_URL, async ({ request }) => {
        const multipartBody = await request.clone().text();
        const readMultipartField = (field: string) =>
          multipartBody.match(new RegExp(`name="${field}"\\r\\n\\r\\n([^\\r]+)`))?.[1] ?? null;
        paymentPayload = {
          anchorInstallmentId: readMultipartField("anchorInstallmentId") ?? "",
          reportedAmount: readMultipartField("reportedAmount") ?? "",
          bankAccountId: readMultipartField("bankAccountId") ?? "",
        };

        const response = {
            submissionId: 999,
            status: "PENDING",
            reportedAmount: "350.00",
            approvedAmount: "0.00",
            rejectedAmount: "0.00",
            paymentCurrency: "ARS",
            exchangeRate: null,
            amountInTripCurrency: "350.00",
            approvedAmountInTripCurrency: "0.00",
            reportedPaymentDate: "2026-03-31",
            paymentMethod: "BANK_TRANSFER",
            fileKey: "",
            adminObservation: null,
            bankAccountId: 1,
            bankAccountDisplayName: "ICBC - Cuenta en pesos",
            bankAccountAlias: "ICBC.PESOS",
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
                totalDue: "200.00",
                paidAmount: "0.00",
                remainingAmount: "200.00",
                reportedAmount: "200.00",
                amountInTripCurrency: "200.00",
                status: "PENDING",
              },
              {
                receiptId: null,
                installmentId: 202,
                installmentNumber: 2,
                dueDate: "2026-07-25",
                totalDue: "200.00",
                paidAmount: "0.00",
                remainingAmount: "200.00",
                reportedAmount: "150.00",
                amountInTripCurrency: "150.00",
                status: "PENDING",
              },
            ],
          };
        PaymentSubmissionDTOSchema.parse(response);
        return HttpResponse.json(response, { status: 201 });
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
      expect(calculationPayload).toMatchObject({
        anchorInstallmentId: 201,
        intent: "MANUAL",
        reportedAmount: "350",
        paymentCurrency: "ARS",
      }),
    );

    expect(await screen.findByText((text) => text.includes("Se imputa en #1, #2"))).toBeInTheDocument();

    const fileInput = document.querySelector("input[type='file']") as HTMLInputElement;
    const file = new File(["test"], "comprobante.jpg", { type: "image/jpeg" });
    fireEvent.change(fileInput, { target: { files: [file] } });

    const submitBtn = await screen.findByRole("button", { name: "Enviar comprobante" });
    await waitFor(() => expect(submitBtn).not.toBeDisabled());
    fireEvent.submit(submitBtn.closest("form") as HTMLFormElement);

    await waitFor(() => expect(paymentPayload).not.toBeNull());
    await screen.findByText("¡Comprobante adjuntado!");
    expect(screen.getAllByText("Bariloche 2026 - Bruno Slavkis").length).toBeGreaterThan(0);
    expect(screen.getAllByText("#1, #2").length).toBeGreaterThan(0);
    expect(screen.getByText("comprobante.jpg")).toBeInTheDocument();

    await waitFor(() =>
      expect(paymentPayload).toMatchObject({
        anchorInstallmentId: "201",
        reportedAmount: "350",
        bankAccountId: "1",
      }),
    );
  });

  it("actualiza automaticamente el monto a reportar al cambiar de pesos a dolares", async () => {
    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 201,
            installmentNumber: 1,
            dueDate: "2026-06-25",
            totalDue: 200,
            paidAmount: 0,
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        const paymentCurrency = body.paymentCurrency;

        if (paymentCurrency === "USD") {
          return HttpResponse.json(makeCalculationResponse({
            body,
            tripCurrency: "ARS",
            remainingAmount: "200.00",
            reportedAmount: "0.20",
            maxAllowedAmount: "0.20",
            exchangeRate: "1000.00",
            amountInTripCurrency: "200.00",
            installments: [
              {
                receiptId: null,
                installmentId: 201,
                installmentNumber: 1,
                dueDate: "2026-06-25",
                totalDue: "200.00",
                paidAmount: "0.00",
                remainingAmount: "200.00",
                reportedAmount: "0.20",
                amountInTripCurrency: "200.00",
                status: null,
              },
            ],
          }));
        }

        const reportedAmount = String(body.reportedAmount ?? 200);
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "ARS",
          remainingAmount: "200.00",
          reportedAmount,
          maxAllowedAmount: "200.00",
          amountInTripCurrency: reportedAmount,
          installments: [
            {
              receiptId: null,
              installmentId: 201,
              installmentNumber: 1,
              dueDate: "2026-06-25",
              totalDue: "200.00",
              paidAmount: "0.00",
              remainingAmount: "200.00",
              reportedAmount,
              amountInTripCurrency: reportedAmount,
              status: null,
            },
          ],
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");

    await waitFor(() => expect(amountInput).toHaveValue(200));
    expect(currencySelect).toHaveValue("ARS");

    fireEvent.change(currencySelect, { target: { value: "USD" } });

    await waitFor(() => {
      expect(currencySelect).toHaveValue("USD");
      expect(amountInput).toHaveValue(0.2);
    });
  });

  it("actualiza automaticamente una cuota en dolares al cambiar a pesos y volver a dolares", async () => {
    const exchangeRate = 1400;

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 301,
            installmentNumber: 1,
            dueDate: "2026-06-25",
            totalDue: 300,
            paidAmount: 0,
            tripCurrency: "USD",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        const paymentCurrency = body.paymentCurrency;

        if (paymentCurrency === "ARS") {
          return HttpResponse.json(makeCalculationResponse({
            body,
            tripCurrency: "USD",
            remainingAmount: "300.00",
            reportedAmount: "420000.00",
            maxAllowedAmount: "420000.00",
            exchangeRate: decimal(exchangeRate),
            amountInTripCurrency: "300.00",
            installments: [
              {
                receiptId: null,
                installmentId: 301,
                installmentNumber: 1,
                dueDate: "2026-06-25",
                totalDue: "300.00",
                paidAmount: "0.00",
                remainingAmount: "300.00",
                reportedAmount: "420000.00",
                amountInTripCurrency: "300.00",
                status: null,
              },
            ],
          }));
        }

        const reportedAmount = String(body.reportedAmount ?? 300);
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "USD",
          remainingAmount: "300.00",
          reportedAmount,
          maxAllowedAmount: "300.00",
          amountInTripCurrency: reportedAmount,
          installments: [
            {
              receiptId: null,
              installmentId: 301,
              installmentNumber: 1,
              dueDate: "2026-06-25",
              totalDue: "300.00",
              paidAmount: "0.00",
              remainingAmount: "300.00",
              reportedAmount,
              amountInTripCurrency: reportedAmount,
              status: null,
            },
          ],
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");

    await waitFor(() => expect(amountInput).toHaveValue(300));
    expect(currencySelect).toHaveValue("USD");

    fireEvent.change(currencySelect, { target: { value: "ARS" } });

    await waitFor(() => {
      expect(currencySelect).toHaveValue("ARS");
      expect(amountInput).toHaveValue(420000);
    });

    fireEvent.change(currencySelect, { target: { value: "USD" } });

    await waitFor(() => {
      expect(currencySelect).toHaveValue("USD");
      expect(amountInput).toHaveValue(300);
    });
  });

  it("ignora una respuesta de conversión anterior a una edición manual", async () => {
    let releaseUsdPreview: (() => void) | undefined;
    const usdPreviewBlocked = new Promise<void>((resolve) => {
      releaseUsdPreview = resolve;
    });

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 401,
            totalDue: 20000,
            paidAmount: 0,
            tripCurrency: "ARS",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        const paymentCurrency = body.paymentCurrency as string;

        if (paymentCurrency === "USD" && body.intent === "REMAINING") {
          await usdPreviewBlocked;
          return HttpResponse.json(makeCalculationResponse({
            body,
            tripCurrency: "ARS",
            remainingAmount: "20000.00",
            reportedAmount: "16.20",
            maxAllowedAmount: "16.20",
            exchangeRate: "1234.56",
            amountInTripCurrency: "19999.87",
          }));
        }

        const reportedAmount = String(body.reportedAmount ?? 20000);
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "ARS",
          remainingAmount: "20000.00",
          reportedAmount,
          maxAllowedAmount: paymentCurrency === "USD" ? "16.20" : "20000.00",
          exchangeRate: paymentCurrency === "USD" ? "1234.56" : null,
          amountInTripCurrency: paymentCurrency === "USD" ? "15234.47" : reportedAmount,
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");
    await waitFor(() => expect(amountInput).toHaveValue(20000));

    fireEvent.change(currencySelect, { target: { value: "USD" } });
    fireEvent.change(amountInput, { target: { value: "12.34" } });
    releaseUsdPreview?.();

    await waitFor(() => expect(currencySelect).toHaveValue("USD"));
    await waitFor(() => expect(amountInput).toHaveValue(12.34));
  });

  it("no vuelve a etiquetar un monto manual al cambiar la moneda", async () => {
    let usdRequests = 0;

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 451,
            totalDue: 20000,
            paidAmount: 0,
            tripCurrency: "ARS",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        if (body.paymentCurrency === "USD") {
          usdRequests += 1;
        }

        const reportedAmount = String(body.reportedAmount ?? (body.paymentCurrency === "USD" ? "16.20" : "20000.00"));
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "ARS",
          remainingAmount: "20000.00",
          reportedAmount,
          maxAllowedAmount: body.paymentCurrency === "USD" ? "16.20" : "20000.00",
          exchangeRate: body.paymentCurrency === "USD" ? "1234.56" : null,
          amountInTripCurrency: body.paymentCurrency === "USD" ? "19999.87" : reportedAmount,
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");
    await waitFor(() => expect(amountInput).toHaveValue(20000));

    fireEvent.change(amountInput, { target: { value: "12.34" } });
    fireEvent.change(currencySelect, { target: { value: "USD" } });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "El monto ingresado corresponde a ARS. Ingresalo nuevamente para calcular en USD.",
    );
    expect(amountInput).toHaveValue(12.34);
    expect(usdRequests).toBe(0);

    fireEvent.change(amountInput, { target: { value: "10.00" } });
    await waitFor(() => expect(usdRequests).toBe(1));
    await waitFor(() => expect(amountInput).toHaveValue(10));
  });

  it("preserva CASE F al alternar ARS a USD y volver a ARS antes de una respuesta lenta", async () => {
    let signalUsdRequest: (() => void) | undefined;
    const usdRequestStarted = new Promise<void>((resolve) => {
      signalUsdRequest = resolve;
    });
    let releaseUsdPreview: (() => void) | undefined;
    const usdPreviewBlocked = new Promise<void>((resolve) => {
      releaseUsdPreview = resolve;
    });

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 501,
            totalDue: 20000,
            paidAmount: 0,
            tripCurrency: "ARS",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        const paymentCurrency = body.paymentCurrency as string;

        if (paymentCurrency === "USD") {
          signalUsdRequest?.();
          await usdPreviewBlocked;
          return HttpResponse.json(makeCalculationResponse({
            body,
            tripCurrency: "ARS",
            remainingAmount: "20000.00",
            reportedAmount: "16.20",
            maxAllowedAmount: "16.20",
            exchangeRate: "1234.56",
            amountInTripCurrency: "19999.87",
          }));
        }

        const reportedAmount = String(body.reportedAmount ?? 20000);
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "ARS",
          remainingAmount: "20000.00",
          reportedAmount,
          maxAllowedAmount: "20000.00",
          amountInTripCurrency: reportedAmount,
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");
    await waitFor(() => expect(amountInput).toHaveValue(20000));

    fireEvent.change(currencySelect, { target: { value: "USD" } });
    await usdRequestStarted;
    fireEvent.change(currencySelect, { target: { value: "ARS" } });
    releaseUsdPreview?.();

    await waitFor(() => expect(currencySelect).toHaveValue("ARS"));
    await waitFor(() => expect(amountInput).toHaveValue(20000));
  });

  it("ignora una respuesta de un contexto anterior tras cambiar fecha y estudiante", async () => {
    let signalOldRequest: (() => void) | undefined;
    const oldRequestStarted = new Promise<void>((resolve) => {
      signalOldRequest = resolve;
    });
    let releaseOldRequest: (() => void) | undefined;
    const oldRequestBlocked = new Promise<void>((resolve) => {
      releaseOldRequest = resolve;
    });

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            tripId: 77,
            studentId: 501,
            installmentId: 601,
            totalDue: 20000,
            tripCurrency: "ARS",
          }),
          makeInstallment({
            tripId: 88,
            tripName: "Córdoba 2026",
            studentId: 502,
            studentName: "Bruno Slavkis",
            studentDni: "45678902",
            installmentId: 602,
            totalDue: 99.29,
            tripCurrency: "ARS",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        const paymentCurrency = body.paymentCurrency as string;

        if (body.anchorInstallmentId === 601 && paymentCurrency === "USD") {
          signalOldRequest?.();
          await oldRequestBlocked;
          return HttpResponse.json(makeCalculationResponse({
            body,
            tripCurrency: "ARS",
            remainingAmount: "20000.00",
            reportedAmount: "16.20",
            maxAllowedAmount: "16.20",
            exchangeRate: "1234.56",
            amountInTripCurrency: "19999.87",
          }));
        }

        const remainingAmount = body.anchorInstallmentId === 602 ? "99.29" : "20000.00";
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "ARS",
          remainingAmount,
          reportedAmount: remainingAmount,
          maxAllowedAmount: remainingAmount,
          amountInTripCurrency: remainingAmount,
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");
    const dateInput = screen.getByLabelText("Fecha de pago");
    const groupSelect = screen.getByLabelText("Seleccioná el viaje");
    await waitFor(() => expect(amountInput).toHaveValue(20000));

    fireEvent.change(currencySelect, { target: { value: "USD" } });
    await oldRequestStarted;
    fireEvent.change(dateInput, { target: { value: "2026-10-01" } });
    fireEvent.change(groupSelect, { target: { value: "88:502" } });
    releaseOldRequest?.();

    await waitFor(() => expect(groupSelect).toHaveValue("88:502"));
    expect(dateInput).toHaveValue("2026-10-01");
    await waitFor(() => expect(currencySelect).toHaveValue("ARS"));
    await waitFor(() => expect(amountInput).toHaveValue(99.29));
  });

  it("preserva la edición manual ante error sin emitir solicitudes de sondeo", async () => {
    let manualUsdRequests = 0;

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            installmentId: 701,
            totalDue: 20000,
            tripCurrency: "ARS",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        if (body.paymentCurrency === "USD" && body.intent === "MANUAL") {
          manualUsdRequests += 1;
          return HttpResponse.json({ message: "Cotización no disponible" }, { status: 503 });
        }

        const reportedAmount = String(body.reportedAmount ?? (body.paymentCurrency === "USD" ? "16.20" : "20000.00"));
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "ARS",
          remainingAmount: "20000.00",
          reportedAmount,
          maxAllowedAmount: body.paymentCurrency === "USD" ? "16.20" : "20000.00",
          exchangeRate: body.paymentCurrency === "USD" ? "1234.56" : null,
          amountInTripCurrency: body.paymentCurrency === "USD" ? "19999.87" : reportedAmount,
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");
    await waitFor(() => expect(amountInput).toHaveValue(20000));

    fireEvent.change(currencySelect, { target: { value: "USD" } });
    await waitFor(() => expect(amountInput).toHaveValue(16.2));
    fireEvent.change(amountInput, { target: { value: "12.34" } });

    await waitFor(() => expect(currencySelect).toHaveValue("USD"));
    expect(amountInput).toHaveValue(12.34);
    await waitFor(() => expect(manualUsdRequests).toBe(1));
  });

  it("muestra CASE G 10.16 y conserva 99.29 como saldo pagable", async () => {
    let arsCalculationRequests = 0;

    server.use(
      http.get(INSTALLMENTS_URL, () =>
        HttpResponse.json([
          makeInstallment({
            tripId: 77,
            studentId: 501,
            installmentId: 801,
            totalDue: 0.01,
            tripCurrency: "USD",
          }),
          makeInstallment({
            tripId: 88,
            tripName: "Córdoba 2026",
            studentId: 502,
            studentName: "Bruno Slavkis",
            studentDni: "45678902",
            installmentId: 802,
            totalDue: 99.29,
            tripCurrency: "USD",
          }),
        ]),
      ),
      http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount, usdBankAccount])),
      http.post(CALCULATION_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        const isCaseG = body.anchorInstallmentId === 801;

        if (isCaseG && body.paymentCurrency === "ARS") {
          arsCalculationRequests += 1;
          return HttpResponse.json(makeCalculationResponse({
            body,
            tripCurrency: "USD",
            remainingAmount: "0.01",
            reportedAmount: "10.16",
            amountInTripCurrency: "0.01",
            maxAllowedAmount: "10.16",
            exchangeRate: "1015.50",
          }));
        }

        const remainingAmount = isCaseG ? "0.01" : "99.29";
        return HttpResponse.json(makeCalculationResponse({
          body,
          tripCurrency: "USD",
          remainingAmount,
          reportedAmount: remainingAmount,
          amountInTripCurrency: remainingAmount,
          maxAllowedAmount: remainingAmount,
        }));
      }),
    );

    renderWithProviders(<UserDashboardPage />);

    const amountInput = await screen.findByLabelText("Monto a reportar");
    const currencySelect = screen.getByLabelText("Moneda en que pagaste");
    const groupSelect = screen.getByLabelText("Seleccioná el viaje");

    await waitFor(() => expect(amountInput).toHaveValue(0.01));
    fireEvent.change(currencySelect, { target: { value: "ARS" } });
    await waitFor(() => expect(amountInput).toHaveValue(10.16));
    expect(arsCalculationRequests).toBe(1);

    fireEvent.change(groupSelect, { target: { value: "88:502" } });
    await waitFor(() => expect(currencySelect).toHaveValue("USD"));
    await waitFor(() => expect(amountInput).toHaveValue(99.29));
    expect(await screen.findByText(/Máximo permitido.*US\$\s*99,29/)).toBeInTheDocument();
  });

  it("usa la fecha actual en horario argentino para la fecha de pago", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-24T02:30:00.000Z"));

    try {
      server.use(
        http.get(INSTALLMENTS_URL, () => HttpResponse.json([makeInstallment()])),
        http.get(BANK_ACCOUNTS_URL, () => HttpResponse.json([bankAccount])),
        http.post(CALCULATION_URL, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>;

          return HttpResponse.json(makeCalculationResponse({
            body,
            tripCurrency: "ARS",
            remainingAmount: "200.00",
            reportedAmount: "200.00",
            maxAllowedAmount: "200.00",
            amountInTripCurrency: "200.00",
          }));
        }),
      );

      const view = renderWithProviders(<UserDashboardPage />);
      expect(screen.getByLabelText("Fecha de pago")).toHaveValue("2026-04-23");
      view.unmount();
    } finally {
      vi.useRealTimers();
    }
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
