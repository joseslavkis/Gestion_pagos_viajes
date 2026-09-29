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
const input = () => document.querySelector("input[type='file']") as HTMLInputElement;
const submit = () => screen.getByRole("button", { name: "Enviar comprobante" });
const file = (name: string, type = "image/png", bytes = "x") => new File([bytes], name, { type });
function upload(...files: File[]) { fireEvent.change(input(), { target: { files } }); }
function amount(name: string, value: string) {
  fireEvent.change(screen.getByLabelText(`Monto de ${name}`), { target: { value } });
}
const confirm = () => screen.getByRole("checkbox", { name: /Confirmo el total/ });
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

  it("uses only the backend opposite-currency equivalent and never its auxiliary token for registration", async () => {
    const requests: Record<string, unknown>[] = [];
    let payment: string | undefined;
    setup({ onCalculation: (body) => requests.push(body), onPayment: (data) => { payment = data; },
      reply: (body) => body.paymentCurrency === "USD" ? { equivalent: "20.03", token: "AUX-DO-NOT-USE" } : {} });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"), file("b.png"));
    amount("a.png", "1.02");
    fireEvent.change(screen.getByLabelText("Moneda de b.png"), { target: { value: "USD" } });
    amount("b.png", "2.00");
    await screen.findByText("Total en ARS: 21.05");
    expect(requests).toEqual([expect.objectContaining({ paymentCurrency: "USD", reportedAmount: "2.00", intent: "MANUAL" })]);
    fireEvent.click(confirm());
    await waitFor(() => expect(requests).toHaveLength(3));
    await waitFor(() => expect(confirm()).toBeChecked());
    await waitFor(() => expect(submit()).toBeEnabled());
    expect(requests).toHaveLength(3);
    expect(requests[1]).toMatchObject({ paymentCurrency: "USD", reportedAmount: "2.00", intent: "MANUAL" });
    expect(requests[2]).toMatchObject({ paymentCurrency: "ARS", reportedAmount: "21.05", intent: "MANUAL" });
    fireEvent.submit(submit().closest("form")!);
    await waitFor(() => expect(payment).toBeDefined());
    expect(payment).toContain("token-ARS-21.05");
    expect(payment).not.toContain("AUX-DO-NOT-USE");
  });

  it("converts ARS to USD when the trip uses USD without client FX", async () => {
    const requests: Record<string, unknown>[] = [];
    setup({ tripCurrency: "USD", onCalculation: (body) => requests.push(body),
      reply: (body) => body.paymentCurrency === "ARS" ? { equivalent: "0.03" } : {} });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png"));
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
    amount("a.png", "45.67");
    await screen.findByText("Total en USD: 0.03");
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    expect(requests).toHaveLength(3);
    expect(requests[1]).toMatchObject({ paymentCurrency: "ARS", reportedAmount: "45.67" });
    expect(requests[2]).toMatchObject({ paymentCurrency: "USD", reportedAmount: "0.03" });
  });

  it("refreshes the auxiliary equivalence at confirmation and requires re-confirmation on a changed quote", async () => {
    let conversions = 0;
    const requests: Record<string, unknown>[] = [];
    setup({ onCalculation: (body) => requests.push(body), reply: (body) => {
      if (body.paymentCurrency !== "USD") return {};
      conversions += 1;
      return { equivalent: conversions === 1 ? "20.00" : "21.00" };
    } });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png"));
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "2");
    await screen.findByText("Total en ARS: 20.00");
    fireEvent.click(confirm());
    await screen.findByText("Total en ARS: 21.00");
    expect(confirm()).not.toBeChecked();
    expect(submit()).toBeDisabled();
    expect(requests).toHaveLength(2);
    fireEvent.click(confirm());
    await waitFor(() => expect(submit()).toBeEnabled());
    expect(requests).toHaveLength(4);
    expect(requests[3]).toMatchObject({ paymentCurrency: "ARS", reportedAmount: "21.00" });
  });

  it("does not enable submission if refreshed auxiliary calculation fails", async () => {
    let conversions = 0;
    const requests: Record<string, unknown>[] = [];
    setup({ onCalculation: (body) => requests.push(body), reply: (body) => {
      if (body.paymentCurrency !== "USD") return {};
      return { failure: ++conversions > 1 };
    } });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png"));
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "2");
    await screen.findByText("Total en ARS: 2.00");
    fireEvent.click(confirm());
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(submit()).toBeDisabled();
    expect(screen.queryByRole("checkbox")).toBeNull();
  });

  it("ignores an in-flight confirmation refresh after receipt or date edits", async () => {
    let release: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    let started: (() => void) | undefined;
    const refreshStarted = new Promise<void>((resolve) => { started = resolve; });
    let conversions = 0;
    const payments: string[] = [];
    setup({ onPayment: (body) => payments.push(body), reply: (body) => {
      if (body.paymentCurrency !== "USD") return {};
      if (++conversions === 2) { started?.(); return { delay: pending, equivalent: "900.00" }; }
      return { equivalent: "20.00" };
    } });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png")); fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "2"); await screen.findByText("Total en ARS: 20.00");
    fireEvent.click(confirm());
    try {
      await refreshStarted;
      amount("a.png", "3");
      fireEvent.change(screen.getByLabelText("Fecha de pago"), { target: { value: "2026-10-01" } });
      release?.();
      await screen.findByText("Total en ARS: 20.00");
      expect(confirm()).not.toBeChecked();
      expect(submit()).toBeDisabled();
      expect(payments).toHaveLength(0);
    } finally { release?.(); }
  });

  it("does not accept an aged auxiliary equivalence for confirmation", async () => {
    setup({ reply: (body) => body.paymentCurrency === "USD" ? { equivalent: "20.00" } : {} });
    await screen.findByText("Primera cuota pendiente", { exact: false });
    upload(file("a.png")); fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "2"); await screen.findByText("Total en ARS: 20.00");
    const now = Date.now();
    const clock = vi.spyOn(Date, "now").mockReturnValue(now + 240_001);
    try {
      fireEvent.change(screen.getByLabelText("Método de pago"), { target: { value: "CASH" } });
      expect(screen.queryByRole("checkbox")).toBeNull();
      expect(submit()).toBeDisabled();
    } finally { clock.mockRestore(); }
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
    if (change === "date") fireEvent.change(screen.getByLabelText("Fecha de pago"), { target: { value: "2026-10-01" } });
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

  it("blocks on auxiliary failure and never sends a final calculation", async () => {
    const requests: Record<string, unknown>[] = [];
    setup({ onCalculation: (body) => requests.push(body), reply: (body) => ({ failure: body.paymentCurrency === "USD" }) });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png")); fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "1");
    expect(await screen.findByRole("alert")).toHaveTextContent("No pudimos calcular");
    expect(submit()).toBeDisabled(); expect(screen.queryByRole("checkbox")).toBeNull();
    expect(requests).toHaveLength(1);
  });

  it("never accepts a stale conversion after a receipt amount changes", async () => {
    let started: (() => void) | undefined;
    const requestStarted = new Promise<void>((resolve) => { started = resolve; });
    let release: (() => void) | undefined;
    const delayed = new Promise<void>((resolve) => { release = resolve; });
    setup({ onCalculation: (body) => {
      if (body.paymentCurrency === "USD" && body.reportedAmount === "1.00") started?.();
    }, reply: (body) => body.paymentCurrency === "USD" && body.reportedAmount === "1.00"
      ? { delay: delayed, equivalent: "999.00" } : { equivalent: "3.00" } });
    await screen.findByText("Adjuntar comprobantes (hasta 5)");
    upload(file("a.png")); fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
    amount("a.png", "1");
    try {
      await requestStarted;
      amount("a.png", "2");
      await screen.findByText("Total en ARS: 3.00");
      release?.();
      await waitFor(() => expect(screen.getByText("Total en ARS: 3.00")).toBeInTheDocument());
    } finally { release?.(); }
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

  it("keeps the bank account anchored to the trip currency, not a receipt currency", async () => {
    setup({ installments: [installment({ tripCurrency: "USD", remainingAmount: "99.29", totalDue: 99.29 })],
      tripCurrency: "USD",
      reply: (body) => body.paymentCurrency === "ARS" ? { equivalent: "10.16" } : {} });
    await waitFor(() => expect(screen.getByLabelText("Cuenta donde acreditaste el pago")).toHaveValue("2"));
    upload(file("a.png")); amount("a.png", "10.16");
    fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "ARS" } });
    await screen.findByText("Total en USD: 10.16");
    expect(screen.getByLabelText("Cuenta donde acreditaste el pago")).toHaveValue("2");
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

  it("does not leave the conversion verification stuck when the auxiliary quote expires mid-refetch", async () => {
    let release: (() => void) | undefined;
    const delayed = new Promise<void>((resolve) => { release = resolve; });
    let usdCalls = 0;
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      setup({ reply: (body) => {
        if (body.paymentCurrency !== "USD") return {};
        usdCalls += 1;
        // The display quote resolves; the confirmation-time refetch stays in flight.
        return usdCalls === 1 ? { equivalent: "20.00" } : { delay: delayed, equivalent: "20.00" };
      } });
      await screen.findByText("Primera cuota pendiente", { exact: false });
      upload(file("a.png"));
      fireEvent.change(screen.getByLabelText("Moneda de a.png"), { target: { value: "USD" } });
      amount("a.png", "2");
      await screen.findByText("Total en ARS: 20.00");
      fireEvent.click(confirm());
      await waitFor(() => expect(screen.getByText(/Verificando la cotización/)).toBeInTheDocument());

      await act(async () => { await vi.advanceTimersByTimeAsync(240_001); });
      await waitFor(() => expect(screen.queryByText(/Verificando la cotización/)).toBeNull());
      // The expired quote drops the total, so the confirmation block is either gone or unchecked.
      const checkbox = screen.queryByRole("checkbox", { name: /Confirmo el total/ });
      if (checkbox) expect(checkbox).not.toBeChecked();
      expect(submit()).toBeDisabled();
    } finally { release?.(); vi.useRealTimers(); }
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
