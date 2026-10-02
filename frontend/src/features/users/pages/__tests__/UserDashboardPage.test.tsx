import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { UserDashboardPage } from "@/features/users/pages/UserDashboardPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

const ROOT = "http://localhost:30002/api/v1";
const installment = (overrides: Record<string, unknown> = {}) => ({
  tripId: 77, tripName: "Mendoza", studentId: 501, studentName: "Martina", studentDni: "45678901",
  installmentId: 101, installmentNumber: 1, dueDate: "2026-03-25", totalDue: 200,
  paidAmount: 0, remainingAmount: "200.00", yellowWarningDays: 5, tripCurrency: "ARS",
  installmentStatus: "YELLOW", latestReceiptStatus: null, uiStatusCode: "DUE_SOON",
  uiStatusLabel: "Vence pronto", uiStatusTone: "yellow", latestReceiptObservation: null,
  userCompletedTrip: false, ...overrides,
});
const account = (currency: "ARS" | "USD", id: number) => ({
  id, bankName: "ICBC", accountLabel: currency, accountHolder: "Holder", accountNumber: "123",
  taxId: "20-123", cbu: "456", alias: `ICBC.${currency}`, currency, active: true, displayOrder: id,
});
const submission = {
  submissionId: 999, status: "PENDING", reportedAmount: "12.00", approvedAmount: "0.00",
  rejectedAmount: "0.00", paymentCurrency: "ARS", exchangeRate: null,
  amountInTripCurrency: "12.00", approvedAmountInTripCurrency: "0.00", reportedPaymentDate: "2026-03-31",
  paymentMethod: "BANK_TRANSFER", fileKey: "", adminObservation: null, bankAccountId: 1,
  bankAccountDisplayName: "ICBC - ARS", bankAccountAlias: "ICBC.ARS", tripId: 77, tripName: "Mendoza",
  tripCurrency: "ARS", studentId: 501, studentName: "Martina", studentDni: "45678901", installments: [],
  source: "CUSTOMER_SUBMISSION",
};

type Reply = { status?: string; equivalent?: string | null; token?: string | null; delay?: Promise<void>;
  installments?: Record<string, unknown>[];
  failure?: boolean };
function setup(options: {
  tripCurrency?: "ARS" | "USD"; reply?: (body: Record<string, unknown>) => Reply;
  onCalculation?: (body: Record<string, unknown>) => void;
  onPayment?: (data: string) => void;
  paymentResponse?: Record<string, unknown>;
  installments?: Record<string, unknown>[];
} = {}) {
  const tripCurrency = options.tripCurrency ?? "ARS";
  server.use(
    http.get(`${ROOT}/payments/my/installments`, () => HttpResponse.json(options.installments ?? [installment({ tripCurrency })])),
    http.get(`${ROOT}/bank-accounts`, () => HttpResponse.json([account("ARS", 1), account("USD", 2)])),
    http.post(`${ROOT}/payments/calculation`, async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      options.onCalculation?.(body);
      const reply = options.reply?.(body) ?? {};
      if (reply.delay) await reply.delay;
      if (reply.failure) return HttpResponse.json({ message: "Unavailable" }, { status: 503 });
      const reportedAmount = String(body.reportedAmount);
      return HttpResponse.json({
        status: reply.status ?? "READY", intent: body.intent, anchorInstallmentId: body.anchorInstallmentId,
        tripCurrency, paymentCurrency: body.paymentCurrency, reportedAmount,
        amountInTripCurrency: reply.equivalent === undefined ? reportedAmount : reply.equivalent,
        anchorRemainingAmount: "200.00", totalPendingAmountInTripCurrency: "200.00",
        maxAllowedAmount: "200.00", tripCurrencyResidual: "0.00", exchangeRate: null,
        reportedPaymentDate: body.reportedPaymentDate, calculationVersion: "2",
        previewToken: reply.token === undefined ? `token-${body.paymentCurrency}-${reportedAmount}` : reply.token,
        installments: reply.installments ?? [], message: reply.status && reply.status !== "READY" ? reply.status : null,
      });
    }),
    http.post(`${ROOT}/payments`, async ({ request }) => {
      options.onPayment?.(await request.clone().text());
      return HttpResponse.json(options.paymentResponse ?? submission, { status: 201 });
    }),
  );
  return renderWithProviders(<UserDashboardPage />);
}
/** Explicitly controllable promise, so a test can order two responses by hand. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((settle) => { resolve = settle; });
  return { promise, resolve: (value: T) => resolve(value) };
}
const input = () => document.querySelector("input[type='file']") as HTMLInputElement;
const submit = () => screen.getByRole("button", { name: "Enviar comprobante" });
const file = (name: string, type = "image/png", bytes = "x") => new File([bytes], name, { type });
function upload(...files: File[]) { fireEvent.change(input(), { target: { files } }); }
function amount(name: string, value: string) {
  fireEvent.change(screen.getByLabelText(`Monto de ${name}`), { target: { value } });
}
const confirm = () => screen.getByRole("checkbox", { name: /Confirmo el total/ });
/** A date guaranteed to differ from the initial "today" value of the form. */
function otherDate(): string {
  const initial = (screen.getByLabelText("Fecha de pago") as HTMLInputElement).value;
  return initial.endsWith("01") ? `${initial.slice(0, -2)}02` : `${initial.slice(0, -2)}01`;
}
const allocation = (installmentId: number, number: number, amount: string, status: string | null) => ({
  receiptId: null, installmentId, installmentNumber: number, dueDate: `2026-0${number + 5}-25`,
  totalDue: "200.00", paidAmount: "0.00", remainingAmount: "200.00",
  reportedAmount: amount, amountInTripCurrency: amount, status,
});

describe("receipt amount payment", () => {
  it("requires a receipt and an entered amount before confirmation or submission", async () => {
    setup();
    expect(await screen.findByText("Adjuntar comprobantes (hasta 5)")).toBeInTheDocument();
    await screen.findByText("Primera cuota pendiente", { exact: false });
    await waitFor(() => expect(screen.getByLabelText("Cuenta donde acreditaste el pago")).toHaveValue("1"));
    expect(submit()).toBeDisabled();
    fireEvent.submit(submit().closest("form")!);
    expect(await screen.findByText("Debés adjuntar al menos un comprobante de pago.")).toBeInTheDocument();
    upload(file("one.png"));
    expect(screen.queryByRole("checkbox")).toBeNull();
    expect(submit()).toBeDisabled();
  });

  it("registers a single same-currency receipt with a distinct final token and global method/account", async () => {
    const requests: Record<string, unknown>[] = [];
    let payment: string | undefined;
    setup({ onCalculation: (body) => requests.push(body), onPayment: (data) => { payment = data; } });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("one.png"));
    amount("one.png", "12.34");
    expect(screen.getByText("Total en ARS: 12.34")).toBeInTheDocument();
    expect(requests).toHaveLength(0);
    fireEvent.change(screen.getByLabelText("Método de pago"), { target: { value: "DEPOSIT" } });
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    expect(requests).toEqual([expect.objectContaining({ intent: "MANUAL", reportedAmount: "12.34", paymentCurrency: "ARS" })]);
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment).toContain("12.34");
    expect(payment).toContain("token-ARS-12.34");
    expect(payment).toContain("DEPOSIT");
    expect(payment).toContain('name="bankAccountId"');
    expect(payment).toContain('name="files"');
    expect(payment).not.toContain('name="amounts"');
  });

  it("sums two same-currency receipts and uploads both with one POST", async () => {
    let payment: string | undefined;
    setup({ onPayment: (data) => { payment = data; } });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"), file("b.png"));
    amount("a.png", "0.10"); amount("b.png", "0.20");
    expect(screen.getByText("Total en ARS: 0.30")).toBeInTheDocument();
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment?.match(/name="files"/g)).toHaveLength(2);
    expect(payment).toContain("0.30");
  });

  it("rejects mixed-currency receipts without converting or registering", async () => {
    const requests: Record<string, unknown>[] = [];
    let payment: string | undefined;
    setup({ onCalculation: (body) => requests.push(body), onPayment: (data) => { payment = data; } });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"), file("b.png"));
    amount("a.png", "100000");
    fireEvent.change(screen.getByLabelText("Moneda de b.png"), { target: { value: "USD" } });
    amount("b.png", "50");
    expect(await screen.findByText(/misma moneda/)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: /Confirmo el total/ })).toBeNull();
    expect(submit()).toBeDisabled();
    expect(requests).toHaveLength(0);
    fireEvent.submit(submit().closest("form")!);
    expect(payment).toBeUndefined();
  });

  describe("herencia de moneda entre comprobantes", () => {
    const receiptCurrencyOf = (name: string) =>
      (screen.getByLabelText(`Moneda de ${name}`) as HTMLSelectElement).value;

    /** The receipt currency is seeded from the trip, so the anchor installment
     *  (which carries the trip currency) must be resolved before uploading. */
    async function openForm(options: { tripCurrency: "ARS" | "USD" }) {
      setup(options);
      expect(await screen.findByText("Primera cuota pendiente", { exact: false })).toBeInTheDocument();
      await screen.findByText("Adjuntar comprobantes (hasta 5)");
    }

    it("inicializa el primer comprobante con la moneda del viaje", async () => {
      await openForm({ tripCurrency: "USD" });
      upload(file("a.png"));
      expect(receiptCurrencyOf("a.png")).toBe("USD");
    });

    it("inicializa el primer comprobante con la moneda del viaje en ARS", async () => {
      await openForm({ tripCurrency: "ARS" });
      upload(file("a.png"));
      expect(receiptCurrencyOf("a.png")).toBe("ARS");
    });

    it("hace que un comprobante nuevo herede la moneda del primero, no la del viaje", async () => {
      await openForm({ tripCurrency: "USD" });
      upload(file("a.png"));
      fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
      upload(file("b.png"));
      expect(receiptCurrencyOf("b.png")).toBe("ARS");
      amount("a.png", "100000");
      amount("b.png", "140000");
      // Both receipts stay in one currency, so the submission is confirmable.
      expect(await screen.findByText("Total en ARS: 240000.00")).toBeInTheDocument();
    });

    it("propaga la moneda del primero a un tercer comprobante", async () => {
      await openForm({ tripCurrency: "USD" });
      upload(file("a.png"));
      fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
      upload(file("b.png"));
      upload(file("c.png"));
      expect(receiptCurrencyOf("b.png")).toBe("ARS");
      expect(receiptCurrencyOf("c.png")).toBe("ARS");
      amount("a.png", "10");
      amount("b.png", "20");
      amount("c.png", "30");
      expect(await screen.findByText("Total en ARS: 60.00")).toBeInTheDocument();
    });

    it("no convierte los comprobantes existentes cuando el primero cambia de moneda", async () => {
      await openForm({ tripCurrency: "USD" });
      upload(file("a.png"));
      fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
      upload(file("b.png"));
      amount("a.png", "100");
      amount("b.png", "200");
      expect(await screen.findByText("Total en ARS: 300.00")).toBeInTheDocument();

      // Mutating receipt 1 must not silently convert receipt 2.
      fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
      expect(receiptCurrencyOf("a.png")).toBe("USD");
      expect(receiptCurrencyOf("b.png")).toBe("ARS");
      // The submission is intentionally left mixed, so it cannot be confirmed.
      expect(await screen.findByText(/misma moneda/)).toBeInTheDocument();
      expect(screen.queryByRole("checkbox", { name: /Confirmo el total/ })).toBeNull();
      expect(submit()).toBeDisabled();
    });

    it("arranca un comprobante nuevo con la moneda del primero tras eliminar y volver a agregar", async () => {
      await openForm({ tripCurrency: "USD" });
      upload(file("a.png"));
      upload(file("b.png"));
      expect(receiptCurrencyOf("a.png")).toBe("USD");
      // Dropping the first receipt promotes the second one to the submission currency.
      fireEvent.click(screen.getByRole("button", { name: "Quitar a.png" }));
      upload(file("c.png"));
      expect(receiptCurrencyOf("c.png")).toBe("USD");
    });
  });

  it("reports ARS receipts in ARS when the trip uses USD and lets the backend compute the equivalence", async () => {
    const requests: Record<string, unknown>[] = [];
    let payment: string | undefined;
    setup({ tripCurrency: "USD", onCalculation: (body) => requests.push(body), onPayment: (data) => { payment = data; },
      reply: (body) => body.paymentCurrency === "ARS" ? { equivalent: "0.03" } : {} });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"));
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
    amount("a.png", "45.67");
    expect(await screen.findByText("Total en ARS: 45.67")).toBeInTheDocument();
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({ paymentCurrency: "ARS", reportedAmount: "45.67" });
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment).toContain("45.67");
    expect(payment).toContain("ARS");
  });

  it("reports USD receipts in USD when the trip uses ARS without client-side FX", async () => {
    const requests: Record<string, unknown>[] = [];
    setup({ onCalculation: (body) => requests.push(body),
      reply: (body) => body.paymentCurrency === "USD" ? { equivalent: "2400.00" } : {} });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png"));
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "2");
    expect(await screen.findByText("Total en USD: 2.00")).toBeInTheDocument();
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({ paymentCurrency: "USD", reportedAmount: "2.00" });
  });

  it("requires re-confirmation when a cross-currency receipt amount changes", async () => {
    setup({});
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png"));
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "2");
    expect(await screen.findByText("Total en USD: 2.00")).toBeInTheDocument();
    fireEvent.click(confirm());
    await waitFor(() => expect(confirm()).toBeChecked());
    amount("a.png", "3");
    expect(await screen.findByText("Total en USD: 3.00")).toBeInTheDocument();
    expect(confirm()).not.toBeChecked();
    expect(submit()).toBeDisabled();
  });

  it("revokes confirmation when a second receipt introduces a mixed currency", async () => {
    setup({});
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png"));
    amount("a.png", "2");
    fireEvent.click(confirm());
    await waitFor(() => expect(confirm()).toBeChecked());
    upload(file("b.png"));
    fireEvent.change(screen.getByLabelText("Moneda de b.png"), { target: { value: "USD" } });
    amount("b.png", "1");
    expect(await screen.findByText(/misma moneda/)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: /Confirmo el total/ })).toBeNull();
    expect(submit()).toBeDisabled();
  });

  it("clears the mixed-currency error once all receipts share one currency again", async () => {
    setup({});
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png"), file("b.png"));
    amount("a.png", "100");
    fireEvent.change(screen.getByLabelText("Moneda de b.png"), { target: { value: "USD" } });
    amount("b.png", "50");
    expect(await screen.findByText(/misma moneda/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Moneda de b.png"), { target: { value: "ARS" } });
    expect(await screen.findByText("Total en ARS: 150.00")).toBeInTheDocument();
    expect(screen.queryByText(/misma moneda/)).toBeNull();
    expect(confirm()).toBeInTheDocument();
  });

  it.each(["", "0", "-1", "1.005", "100000000", "oops"])("rejects invalid amount %s", async (value) => {
    const requests: unknown[] = [];
    setup({ onCalculation: (body) => requests.push(body) });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png")); amount("a.png", value);
    expect(submit()).toBeDisabled();
    expect(screen.queryByRole("checkbox")).toBeNull();
    expect(requests).toHaveLength(0);
  });

  it("caps receipts at five by disabling upload instead of raising a file error", async () => {
    setup(); await screen.findByText("Adjuntar comprobantes (hasta 5)");
    expect(input()).toBeEnabled();
    upload(...Array.from({ length: 4 }, (_, i) => file(`${i}.png`)));
    expect(input()).toBeEnabled();

    // 4 receipts + a two-file selection accepts only what completes five, with no persistent error.
    upload(file("four.png"), file("five.png"));
    expect(screen.getByText("Máximo de 5 comprobantes alcanzado")).toBeInTheDocument();
    expect(input()).toBeDisabled();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getAllByRole("button", { name: /^Quitar / })).toHaveLength(5);
    expect(screen.getByLabelText("Monto de four.png")).toBeInTheDocument();
    expect(screen.queryByLabelText("Monto de five.png")).toBeNull();

    // There is no normal way to select a sixth receipt once the cap is reached.
    upload(file("six.png"));
    expect(screen.getAllByRole("button", { name: /^Quitar / })).toHaveLength(5);
    expect(screen.queryByLabelText("Monto de six.png")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();

    // Removing one receipt re-enables uploading.
    fireEvent.click(screen.getByRole("button", { name: "Quitar four.png" }));
    expect(input()).toBeEnabled();
    expect(screen.queryByText("Máximo de 5 comprobantes alcanzado")).toBeNull();
    upload(file("six.png"));
    expect(screen.getByLabelText("Monto de six.png")).toBeInTheDocument();
  });

  it("keeps fileError reserved for real file errors and preserves already selected files", async () => {
    setup(); await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"));
    upload(new File([new Uint8Array(5 * 1024 * 1024 + 1)], "huge.pdf", { type: "application/pdf" }));
    expect(screen.getByRole("alert")).toHaveTextContent("5 MB");
    expect(screen.getByLabelText("Monto de a.png")).toBeInTheDocument();
    upload(file("bad.txt", "text/plain"));
    expect(screen.getByRole("alert")).toHaveTextContent("JPG, PNG, WEBP o PDF");
    expect(screen.getByLabelText("Monto de a.png")).toBeInTheDocument();
  });

  it("accumulates receipts across successive picker openings instead of replacing them", async () => {
    let payment: string | undefined;
    setup({ onPayment: (data) => { payment = data; } });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");

    // First picker opening: a single receipt.
    upload(file("a.png"));
    expect(screen.getByLabelText("Monto de a.png")).toBeInTheDocument();
    expect(screen.queryByLabelText("Monto de b.png")).toBeNull();

    // Second picker opening must add to the current selection, not replace it.
    upload(file("b.png"));
    expect(screen.getByLabelText("Monto de a.png")).toBeInTheDocument();
    expect(screen.getByLabelText("Monto de b.png")).toBeInTheDocument();
    expect(screen.getByText("2 de 5 archivos seleccionados · agregar más")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();

    // Third picker opening keeps all three receipts visible.
    upload(file("c.png"));
    expect(screen.getByLabelText("Monto de a.png")).toBeInTheDocument();
    expect(screen.getByLabelText("Monto de b.png")).toBeInTheDocument();
    expect(screen.getByLabelText("Monto de c.png")).toBeInTheDocument();
    expect(screen.getByText("3 de 5 archivos seleccionados · agregar más")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();

    // The accumulated set, not just the last selection, reaches the POST body.
    amount("a.png", "10"); amount("b.png", "20"); amount("c.png", "30");
    expect(screen.getByText("Total en ARS: 60.00")).toBeInTheDocument();
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment?.match(/name="files"/g)).toHaveLength(3);
  });

  it("removes a receipt, clears confirmation and recomputes the total", async () => {
    setup(); await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"), file("b.png")); amount("a.png", "10"); amount("b.png", "2");
    fireEvent.click(confirm()); await waitFor(() => expect(submit()).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Quitar b.png" }));
    expect(submit()).toBeDisabled();
    expect(confirm()).not.toBeChecked();
    expect(screen.getByText("Total en ARS: 10.00")).toBeInTheDocument();
  });

  it.each(["amount", "currency", "files", "date", "trip"])("revokes confirmation immediately on %s edit", async (change) => {
    setup({ installments: [installment(), installment({ tripId: 88, installmentId: 201, studentId: 502 })] });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png")); amount("a.png", "10"); fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    if (change === "amount") amount("a.png", "11");
    if (change === "currency") fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    if (change === "files") upload(file("b.png"));
    // Must differ from the initial date, otherwise React fires no onChange and
    // the confirmation is never revoked. Hardcoding a calendar date made this
    // test silently time-bombed.
    if (change === "date") fireEvent.change(screen.getByLabelText("Fecha de pago"), { target: { value: otherDate() } });
    if (change === "trip") fireEvent.change(screen.getByLabelText("Seleccioná el viaje"), { target: { value: "88:502" } });
    expect(submit()).toBeDisabled();
    if (screen.queryByRole("checkbox")) expect(confirm()).not.toBeChecked();
  });

  it.each(["EXPIRED", "AMOUNT_EXCEEDS_BALANCE", "UNPAYABLE", "QUOTE_UNAVAILABLE"]) (
    "blocks registration for %s final calculation", async (status) => {
      let payments = 0;
      setup({ reply: () => ({ status, token: null }), onPayment: () => { payments++; } });
      await screen.findByText("Adjuntar comprobantes (hasta 5)");
      upload(file("a.png")); amount("a.png", "12"); fireEvent.click(confirm());
      expect(await screen.findByRole("alert")).toHaveTextContent(status);
      expect(submit()).toBeDisabled(); fireEvent.submit(submit().closest("form")!);
      expect(payments).toBe(0);
    },
  );

  it("blocks submission when the single-currency final calculation fails", async () => {
    const requests: Record<string, unknown>[] = [];
    setup({ onCalculation: (body) => requests.push(body), reply: () => ({ failure: true }) });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png")); fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "1");
    expect(await screen.findByText("Total en USD: 1.00")).toBeInTheDocument();
    fireEvent.click(confirm());
    expect(await screen.findByRole("alert")).toHaveTextContent("No pudimos calcular");
    expect(submit()).toBeDisabled();
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({ paymentCurrency: "USD", reportedAmount: "1.00" });
  });

  it("never accepts a stale total after a receipt amount changes", async () => {
    setup({});
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"));
    amount("a.png", "1");
    expect(await screen.findByText("Total en ARS: 1.00")).toBeInTheDocument();
    amount("a.png", "2");
    expect(await screen.findByText("Total en ARS: 2.00")).toBeInTheDocument();
    expect(submit()).toBeDisabled();
  });

  it("blocks submission on a failed final query even if the old READY response stays cached", async () => {
    let failures = false;
    const { queryClient } = setup({ reply: () => ({ failure: failures }) });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png")); amount("a.png", "12"); fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    failures = true;
    await queryClient.invalidateQueries({ queryKey: ["payments", "calculation"] });
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("No pudimos calcular"));
    expect(submit()).toBeDisabled();
  });

  it("does not allow submission while a final calculation is pending", async () => {
    let release: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    setup({ reply: () => ({ delay: pending }) });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png")); amount("a.png", "4.00"); fireEvent.click(confirm());
    expect(submit()).toBeDisabled();
    try {
      release?.();
      await waitFor(() => expect(submit()).toBeEnabled());
    } finally { release?.(); }
  });

  it("ignora una respuesta HTTP tardía de un cálculo anterior sin pisar el más reciente", async () => {
    // Deliberately out-of-order delivery: request A is parked until request B
    // has already been adopted, so a late A can only be a genuine stale write.
    const gateA = deferred<void>();
    const gateB = deferred<void>();
    const requests: Record<string, unknown>[] = [];
    let payment: string | undefined;
    const { queryClient } = setup({
      onCalculation: (body) => requests.push(body),
      onPayment: (data) => { payment = data; },
      reply: (body) => {
        if (body.reportedAmount === "100.00") return { delay: gateA.promise, token: "token-A" };
        if (body.reportedAmount === "200.00") return { delay: gateB.promise, token: "token-B" };
        return {};
      },
    });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png"));

    // Request A: confirmed at 100.00 and left in flight on purpose.
    amount("a.png", "100.00");
    fireEvent.click(confirm());
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toMatchObject({ reportedAmount: "100.00", paymentCurrency: "ARS" });
    expect(submit()).toBeDisabled();

    // The user changes the amount, which revokes the confirmation of A.
    amount("a.png", "200.00");
    expect(screen.getByText("Total en ARS: 200.00")).toBeInTheDocument();

    // Request B is confirmed and answered first, so the UI adopts B.
    fireEvent.click(confirm());
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]).toMatchObject({ reportedAmount: "200.00", paymentCurrency: "ARS" });
    gateB.resolve();
    await waitFor(() => expect(submit()).toBeEnabled());

    // Only now the superseded request A answers. Wait until its response has
    // really reached the client, otherwise "nothing changed" would pass
    // trivially without the stale write ever happening.
    gateA.resolve();
    await waitFor(() => {
      const delivered = queryClient.getQueryCache()
        .getAll()
        .some((query) => (query.state.data as { previewToken?: string } | undefined)?.previewToken === "token-A");
      expect(delivered).toBe(true);
    });

    // A landed in its own cache slot, which is what keeps it from ever being
    // read back as the current calculation.
    const slotOf = (token: string) => queryClient.getQueryCache()
      .getAll()
      .filter((query) => (query.state.data as { previewToken?: string } | undefined)?.previewToken === token)
      .map((query) => JSON.stringify(query.queryKey));
    expect(slotOf("token-A")).toHaveLength(1);
    expect(slotOf("token-B")).toHaveLength(1);
    expect(slotOf("token-A")[0]).not.toBe(slotOf("token-B")[0]);

    // A must not be able to drive the current state.
    expect(screen.getByText("Total en ARS: 200.00")).toBeInTheDocument();
    await waitFor(() => expect(submit()).toBeEnabled());
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment).toContain("token-B");
    expect(payment).not.toContain("token-A");
    expect(payment).toContain("200.00");
  });

  it("keeps submission blocked for an in-review trip", async () => {
    setup({ installments: [installment({ latestReceiptStatus: "PENDING", uiStatusCode: "UNDER_REVIEW" })] });
    expect(await screen.findByText(/Esta inscripción tiene comprobantes pendientes de revisión/)).toBeInTheDocument();
    expect(submit()).toBeDisabled();
    expect(screen.getByLabelText("Fecha de pago")).toBeDisabled();
  });

  it("preserves status, rejection, partial-payment and multi-installment display through a submitted payment", async () => {
    const rows = [
      installment({ installmentId: 101, latestReceiptStatus: "PENDING", uiStatusCode: "UNDER_REVIEW",
        uiStatusLabel: "En revisión" }),
      installment({ installmentId: 102, installmentNumber: 2, latestReceiptStatus: "REJECTED",
        uiStatusCode: "RECEIPT_REJECTED", uiStatusLabel: "Comprobante rechazado",
        latestReceiptObservation: "El comprobante está borroso." }),
      installment({ installmentId: 103, installmentNumber: 3, uiStatusCode: "UP_TO_DATE",
        uiStatusLabel: "Al día", paidAmount: 50, remainingAmount: "150.00" }),
      installment({ tripId: 88, tripName: "Bariloche 2026", studentId: 502, studentName: "Bruno",
        installmentId: 201 }),
      installment({ tripId: 88, tripName: "Bariloche 2026", studentId: 502, studentName: "Bruno",
        installmentId: 202, installmentNumber: 2, dueDate: "2026-07-25" }),
    ];
    const allocations = [allocation(201, 1, "200.00", null), allocation(202, 2, "150.00", null)];
    let payment: string | undefined;
    setup({ installments: rows, onPayment: (body) => { payment = body; },
      reply: () => ({ installments: allocations }),
      paymentResponse: { ...submission, tripId: 88, tripName: "Bariloche 2026", studentId: 502,
        studentName: "Bruno", reportedAmount: "350.00", amountInTripCurrency: "350.00",
        installments: allocations.map((row) => ({ ...row, status: "PENDING" })) },
    });
    expect(await screen.findByText("Comprobante rechazado")).toBeInTheDocument();
    expect(screen.getAllByText("En revisión").length).toBeGreaterThan(0);
    expect(screen.getByText("⚠ El comprobante está borroso.")).toBeInTheDocument();
    expect(screen.getByText("Tu comprobante está siendo revisado por el administrador")).toBeInTheDocument();
    expect(screen.getByText(/Abonado:/)).toHaveTextContent("Resta:");
    fireEvent.change(screen.getByLabelText("Seleccioná el viaje"), { target: { value: "88:502" } });
    await waitFor(() => expect(screen.getByLabelText("Seleccioná el viaje")).toHaveValue("88:502"));
    await screen.findByText("Primera cuota pendiente #1", { exact: false });
    upload(file("comprobante.jpg", "image/jpeg")); amount("comprobante.jpg", "350");
    fireEvent.click(confirm());
    await screen.findByText((text) => text.includes("Se imputa en #1, #2"));
    await waitFor(() => expect(submit()).toBeEnabled());
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toContain("350.00"));
    expect(payment).toContain('name="anchorInstallmentId"\r\n\r\n201');
    await screen.findByText("¡Pago reportado!");
    expect(screen.getByText("comprobante.jpg")).toBeInTheDocument();
  });

  it("anchors the bank account to the receipts currency, not the trip currency", async () => {
    setup({ installments: [installment({ tripCurrency: "USD", remainingAmount: "99.29", totalDue: 99.29 })],
      tripCurrency: "USD",
      reply: (body) => body.paymentCurrency === "ARS" ? { equivalent: "0.01" } : {} });
    await waitFor(() => expect(screen.getByLabelText("Cuenta donde acreditaste el pago")).toHaveValue("2"));
    upload(file("a.png")); amount("a.png", "10.16");
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
    await screen.findByText("Total en ARS: 10.16");
    await waitFor(() => expect(screen.getByLabelText("Cuenta donde acreditaste el pago")).toHaveValue("1"));
    expect(confirm()).not.toBeChecked();
  });

  it("requires re-confirmation when an edited amount returns to its prior value", async () => {
    setup(); await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png")); amount("a.png", "10"); fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    amount("a.png", "11"); amount("a.png", "10");
    expect(confirm()).not.toBeChecked();
    expect(submit()).toBeDisabled();
  });

  it("requires a fresh final calculation after unchecking and rechecking the same total", async () => {
    const requests: Record<string, unknown>[] = [];
    setup({ onCalculation: (body) => requests.push(body) });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png")); amount("a.png", "10"); fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    fireEvent.click(confirm());
    expect(submit()).toBeDisabled();
    fireEvent.click(confirm());
    await waitFor(() => expect(requests).toHaveLength(2));
    await waitFor(() => expect(submit()).toBeEnabled());
  });

  it("rejects a final token once its local safety window has elapsed", async () => {
    setup(); await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png")); amount("a.png", "10"); fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    const realNow = Date.now();
    const clock = vi.spyOn(Date, "now").mockReturnValue(realNow + 240_001);
    try {
      fireEvent.change(screen.getByLabelText("Método de pago"), { target: { value: "CASH" } });
      expect(submit()).toBeDisabled();
      fireEvent.submit(submit().closest("form")!);
      expect(submit()).toBeDisabled();
    } finally { clock.mockRestore(); }
  });

  it("clears the explicit confirmation when the final token's local safety window elapses", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      setup(); await screen.findByText("Primera cuota pendiente", { exact: false });
      upload(file("a.png")); amount("a.png", "10"); fireEvent.click(confirm());
      await waitFor(() => expect(submit()).toBeEnabled());
      expect(confirm()).toBeChecked();
      await act(async () => { await vi.advanceTimersByTimeAsync(240_001); });
      expect(confirm()).not.toBeChecked();
      expect(submit()).toBeDisabled();
    } finally { vi.useRealTimers(); }
  });

  it("sends the receipts currency as paymentCurrency when registering a cross-currency payment", async () => {
    let payment: string | undefined;
    const requests: Record<string, unknown>[] = [];
    setup({ installments: [installment({ tripCurrency: "USD", remainingAmount: "99.29", totalDue: 99.29 })],
      tripCurrency: "USD", onCalculation: (body) => requests.push(body),
      onPayment: (data) => { payment = data; },
      reply: (body) => body.paymentCurrency === "ARS" ? { equivalent: "0.01" } : {} });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"));
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
    amount("a.png", "10.16");
    expect(await screen.findByText("Total en ARS: 10.16")).toBeInTheDocument();
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    expect(requests[0]).toMatchObject({ paymentCurrency: "ARS", reportedAmount: "10.16" });
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment).toContain("10.16");
    expect(payment).toContain("ARS");
  });

  it("revokes the previous preview object URL and promotes the next image when the first receipt is removed", async () => {
    const created: string[] = [];
    const revoked: string[] = [];
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn(() => {
      const url = `blob:preview/${created.length + 1}`;
      created.push(url);
      return url;
    }) as unknown as typeof URL.createObjectURL;
    URL.revokeObjectURL = vi.fn((url: string | URL) => {
      revoked.push(String(url));
    }) as unknown as typeof URL.revokeObjectURL;
    try {
      setup(); await screen.findByText("Primera cuota pendiente", { exact: false });
      upload(file("a.png"), file("b.png"));
      expect(created).toEqual(["blob:preview/1"]);
      expect(screen.getByAltText("Vista previa")).toHaveAttribute("src", "blob:preview/1");

      fireEvent.click(screen.getByRole("button", { name: "Quitar a.png" }));
      expect(revoked).toContain("blob:preview/1");
      expect(screen.getByAltText("Vista previa")).toHaveAttribute("src", "blob:preview/2");

      // Removing the promoted preview leaves no stale object URL and no preview element.
      fireEvent.click(screen.getByRole("button", { name: "Quitar b.png" }));
      expect(revoked).toContain("blob:preview/2");
      expect(screen.queryByAltText("Vista previa")).toBeNull();
    } finally {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
    }
  });

  it("drops the preview when the next receipt is not an image and keeps revoke discipline", async () => {
    const revoked: string[] = [];
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn(() => "blob:preview/1") as unknown as typeof URL.createObjectURL;
    URL.revokeObjectURL = vi.fn((url: string | URL) => {
      revoked.push(String(url));
    }) as unknown as typeof URL.revokeObjectURL;
    try {
      setup(); await screen.findByText("Primera cuota pendiente", { exact: false });
      upload(file("a.png"), file("b.pdf", "application/pdf"));
      expect(screen.getByAltText("Vista previa")).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "Quitar a.png" }));
      expect(revoked).toContain("blob:preview/1");
      expect(screen.queryByAltText("Vista previa")).toBeNull();
    } finally {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
    }
  });

  it("uses Argentina's calendar date as the initial reported date", async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date("2026-04-24T02:30:00.000Z"));
    try {
      setup(); expect(screen.getByLabelText("Fecha de pago")).toHaveValue("2026-04-23");
    } finally { vi.useRealTimers(); }
  });
});

describe("total confirmation control", () => {
  const block = () => confirm().closest("label") as HTMLLabelElement;

  it("shows a full-card confirmation for a single valid receipt", async () => {
    setup(); await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("one.png")); amount("one.png", "150.00");
    expect(screen.getByText("Total en ARS: 150.00")).toBeInTheDocument();
    expect(confirm()).toBeInTheDocument();
    expect(block()).toHaveTextContent("Confirmo el total de 150.00 ARS para estos comprobantes.");
    // The label wraps both the box and the copy, so the whole card is the hit area.
    expect(block()).toContainElement(confirm());
  });

  it("shows the same confirmation for multiple valid receipts with the summed total", async () => {
    setup(); await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"), file("b.png"), file("c.png"));
    amount("a.png", "100.00"); amount("b.png", "50.00"); amount("c.png", "25.50");
    expect(screen.getByText("Total en ARS: 175.50")).toBeInTheDocument();
    expect(block()).toHaveTextContent("Confirmo el total de 175.50 ARS para estos comprobantes.");
  });

  it("toggles and completes the normal flow from the card copy, not only the box", async () => {
    let payment: string | undefined;
    setup({ onPayment: (data) => { payment = data; } });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("one.png")); amount("one.png", "150.00");
    expect(submit()).toBeDisabled();
    fireEvent.click(screen.getByText(/Confirmo el total de 150\.00 ARS/));
    await waitFor(() => expect(confirm()).toBeChecked());
    await waitFor(() => expect(submit()).toBeEnabled());
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment).toContain("150.00");
  });

  it("stays keyboard operable through the native checkbox", async () => {
    setup(); await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("one.png")); amount("one.png", "150.00");
    confirm().focus();
    expect(confirm()).toHaveFocus();
    fireEvent.keyDown(confirm(), { key: " ", code: "Space" });
    fireEvent.click(confirm());
    await waitFor(() => expect(confirm()).toBeChecked());
  });

  it("invalidates the confirmed card when an amount changes afterwards", async () => {
    setup(); await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"), file("b.png")); amount("a.png", "100.00"); amount("b.png", "50.00");
    fireEvent.click(confirm());
    await waitFor(() => expect(confirm()).toBeChecked());
    await waitFor(() => expect(submit()).toBeEnabled());
    amount("b.png", "60.00");
    expect(confirm()).not.toBeChecked();
    expect(submit()).toBeDisabled();
    expect(screen.getByText("Total en ARS: 160.00")).toBeInTheDocument();
  });
});
