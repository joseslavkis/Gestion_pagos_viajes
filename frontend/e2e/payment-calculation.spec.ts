import { expect, request, test, type APIRequestContext, type Locator, type Page } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const apiUrl = requiredEnvironment("PAYMENT_E2E_API_URL");
const fxCallLog = requiredEnvironment("PAYMENT_E2E_FX_CALL_LOG");
const adminEmail = requiredEnvironment("PAYMENT_E2E_ADMIN_EMAIL");
const adminPassword = requiredEnvironment("PAYMENT_E2E_ADMIN_PASSWORD");

const CASE_F_FAST_DATE = "2026-01-15";
const CASE_F_SLOW_DATE = "2026-01-14";
const CASE_G_DATE = "2026-01-13";
const CASE_J_DATE = "2026-01-15";
// Deterministic provider date whose rate is 1200.00, so the ARS -> USD direction
// converts ARS 240000.00 into exactly USD 200.00 with no rounding ambiguity.
const CASE_MULTI_RECEIPT_DATE = "2026-01-16";
const CASE_MULTI_RECEIPT_RATE = "1200.00";
const CASE_MULTI_RECEIPT_EXPECTED_USD = "200.00";
const ADMIN_REVIEW_DATE = "2026-09-03";
const executeFile = promisify(execFile);

type SeededContext = {
  accessToken: string;
  adminAccessToken: string;
  refreshToken: string | null;
  userEmail: string;
  caseFTripName: string;
  caseGTripName: string;
  validCentsTripName: string;
  threeInstallmentTripName: string;
  threeInstallmentTripId: number;
  caseJTripName: string;
  caseJTripId: number;
  multiReceiptArsTripName: string;
  multiReceiptUsdTripName: string;
  multiReceiptUsdTripId: number;
  adminCurrencyTripName: string;
  adminCurrencyTripId: number;
};

let api: APIRequestContext;
let seeded: SeededContext;

test.describe.configure({ mode: "serial" });
test.use({ timezoneId: "America/Argentina/Buenos_Aires" });

test.beforeAll(async () => {
  api = await request.newContext({ baseURL: apiUrl });
  seeded = await seedPaymentContexts(api);
});

test.afterAll(async () => {
  await api.dispose();
});

test.beforeEach(async ({ page }) => {
  await writeFile(fxCallLog, "", "utf8");
  await page.addInitScript((tokens) => {
    window.localStorage.setItem("pagos-viajes-auth-tokens", JSON.stringify(tokens));
  }, {
    accessToken: seeded.accessToken,
    refreshToken: seeded.refreshToken,
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Panel de pagos" })).toBeVisible();
});

test("CASE F keeps the reported total in the receipts currency across an ARS to USD to ARS toggle", async ({ page }) => {
  await selectTrip(page, seeded.caseFTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_F_FAST_DATE);

  const receipt = "case-f-fast.png";
  await attachReceipt(page, receipt);
  await receiptAmount(page, receipt).fill("10");
  await expect(page.getByText("Total en ARS: 10.00")).toBeVisible();
  // A receipt already denominated in the trip currency needs no quote at all.
  expect(await readFxCalls()).toEqual([]);

  // Switching the receipt currency changes the reported total currency; no
  // conversion is applied client-side. The quote is only requested for the
  // backend-authoritative equivalence once the total is confirmed.
  await receiptCurrency(page, receipt).selectOption("USD");
  await expect(page.getByText("Total en USD: 10.00")).toBeVisible();

  // Returning to the trip currency restores the original reported total.
  await receiptCurrency(page, receipt).selectOption("ARS");
  await expect(page.getByText("Total en ARS: 10.00")).toBeVisible();
});

test("CASE F rejects mixed currencies within a single submission", async ({ page }) => {
  await selectTrip(page, seeded.caseFTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_F_SLOW_DATE);

  const first = "case-f-slow-a.png";
  const second = "case-f-slow-b.png";
  await attachReceipt(page, first);
  await receiptAmount(page, first).fill("10");
  await attachReceipt(page, second);
  await receiptCurrency(page, second).selectOption("USD");
  await receiptAmount(page, second).fill("5");
  await expect(page.getByText(/misma moneda/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Enviar comprobante" })).toBeDisabled();
  expect(await readFxCalls()).toEqual([]);
});

test("CASE G reports the explicit amount in its own currency and leaves conversion to the backend", async ({ page }) => {
  await selectTrip(page, seeded.caseGTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_G_DATE);

  // The receipt is reported in ARS; the trip is in USD. The total stays in ARS
  // and the backend computes the USD equivalence on confirmation.
  const crossCurrency = "case-g-cross-currency.png";
  await attachReceipt(page, crossCurrency);
  await receiptCurrency(page, crossCurrency).selectOption("ARS");
  await receiptAmount(page, crossCurrency).fill("10.16");
  await expect(page.getByText("Total en ARS: 10.16")).toBeVisible();

  // Drop the cross-currency receipt so the next trip is measured from a clean state.
  await page.getByRole("button", { name: `Quitar ${crossCurrency}` }).click();

  // A receipt already in the trip currency is priced without any extra quote, valid cents included.
  await selectTrip(page, seeded.validCentsTripName);
  const sameCurrency = "case-g-valid-cents.png";
  await attachReceipt(page, sameCurrency);
  await receiptAmount(page, sameCurrency).fill("99.29");
  await expect(page.getByText("Total en USD: 99.29")).toBeVisible();
  expect(await readFxCalls()).toEqual([]);
});

test("registers two ARS receipts in one submission, preserving both attachments", async ({ page }) => {
  await selectTrip(page, seeded.multiReceiptArsTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_MULTI_RECEIPT_DATE);

  const first = "multi-ars-a.png";
  const second = "multi-ars-b.png";
  await attachReceipt(page, first);
  // The second receipt inherits the currency of the first one, so both stay in ARS.
  await attachReceipt(page, second);
  await expect(receiptCurrency(page, second)).toHaveValue("ARS");

  await receiptAmount(page, first).fill("100000");
  await receiptAmount(page, second).fill("140000");
  await expect(page.getByText("Total en ARS: 240000.00")).toBeVisible();

  const submissionId = await submitAndCaptureForm(page, "240000.00", "ARS");

  // Read back from the backend, so both attachments and the reported semantics
  // are proven to survive the real registration.
  const persisted = await reloadedPayment(seeded.accessToken, submissionId);
  expect(persisted).toMatchObject({
    status: "PENDING",
    reportedAmount: "240000.00",
    paymentCurrency: "ARS",
    // Same-currency submission: the equivalence is the reported amount itself.
    amountInTripCurrency: "240000.00",
    tripCurrency: "ARS",
  });
  expect(persisted.fileKeys).toHaveLength(2);
  expect(new Set(persisted.fileKeys).size).toBe(2);
  expect(sumCents(persisted.installments as Array<Record<string, unknown>>, "reportedAmount"))
    .toBe(moneyToCents("240000.00"));
  expect(sumCents(persisted.installments as Array<Record<string, unknown>>, "amountInTripCurrency"))
    .toBe(moneyToCents("240000.00"));
  // Same-currency payments never need a quote.
  expect(await readFxCalls()).toEqual([]);
});

test("converts ARS receipts into a USD trip end to end, keeping the reported amount in ARS", async ({ page }) => {
  await selectTrip(page, seeded.multiReceiptUsdTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_MULTI_RECEIPT_DATE);

  const first = "multi-usd-a.png";
  const second = "multi-usd-b.png";
  // The trip is USD, so the first receipt starts in USD and is switched to ARS.
  await attachReceipt(page, first);
  await receiptCurrency(page, first).selectOption("ARS");
  await receiptAmount(page, first).fill("100000");
  // The second receipt inherits ARS from the first one.
  await attachReceipt(page, second);
  await expect(receiptCurrency(page, second)).toHaveValue("ARS");
  await receiptAmount(page, second).fill("140000");

  // The frontend keeps the total in the receipts currency; no client-side conversion.
  await expect(page.getByText("Total en ARS: 240000.00")).toBeVisible();
  await confirmTotal(page, "240000.00", "ARS");
  await expect.poll(readFxCalls).toEqual([CASE_MULTI_RECEIPT_DATE]);

  const submissionId = await submitAndCaptureForm(page, "240000.00", "ARS");

  // The reported amount was never converted before reaching the backend: it
  // comes back from the backend still denominated in the receipts currency.
  const persisted = await reloadedPayment(seeded.accessToken, submissionId);
  expect(persisted).toMatchObject({
    status: "PENDING",
    // Reported in the real receipts currency.
    reportedAmount: "240000.00",
    paymentCurrency: "ARS",
    // The only backend-computed value, expressed in the trip currency.
    amountInTripCurrency: CASE_MULTI_RECEIPT_EXPECTED_USD,
    tripCurrency: "USD",
  });
  // 240000.00 ARS / 1200.00 = 200.00 USD, the deterministic provider rate.
  expect(String(persisted.exchangeRate)).toBe(CASE_MULTI_RECEIPT_RATE);
  expect(persisted.fileKeys).toHaveLength(2);
  expect(sumCents(persisted.installments as Array<Record<string, unknown>>, "reportedAmount"))
    .toBe(moneyToCents("240000.00"));
  expect(sumCents(persisted.installments as Array<Record<string, unknown>>, "amountInTripCurrency"))
    .toBe(moneyToCents(CASE_MULTI_RECEIPT_EXPECTED_USD));

  // The lifecycle keeps the same semantics: reported stays ARS while the
  // imputed amount stays USD, all the way through the approval.
  const adminPage = await page.context().newPage();
  await adminPage.addInitScript((accessToken) => {
    window.localStorage.setItem("pagos-viajes-auth-tokens", JSON.stringify({ accessToken, refreshToken: null }));
  }, seeded.adminAccessToken);
  await adminPage.goto("/payments/pending-review");
  const reviewCard = adminPage.locator("article").filter({ hasText: seeded.multiReceiptUsdTripName });
  await expect(reviewCard).toHaveCount(1);
  await openAmountReview(reviewCard);
  await reviewCard.getByRole("button", { name: "Guardar decisión" }).click();
  await expect(reviewCard).toHaveCount(0);

  const approved = await reloadedPayment(seeded.accessToken, submissionId);
  expect(approved).toMatchObject({
    status: "APPROVED",
    reportedAmount: "240000.00",
    approvedAmount: "240000.00",
    paymentCurrency: "ARS",
    amountInTripCurrency: CASE_MULTI_RECEIPT_EXPECTED_USD,
    approvedAmountInTripCurrency: CASE_MULTI_RECEIPT_EXPECTED_USD,
  });
  const paidInstallments = (await getJson(
    api, "/api/v1/payments/my/installments", seeded.accessToken,
  ) as Array<Record<string, unknown>>)
    .filter((installment) => installment.tripId === seeded.multiReceiptUsdTripId);
  expect(paidInstallments.map((installment) => moneyToCents(installment.paidAmount)))
    .toEqual([moneyToCents(CASE_MULTI_RECEIPT_EXPECTED_USD)]);
  await adminPage.close();
});

test("allocates a manual 500 across three 240 installments, then approves and voids the exact credits", async ({ page, context }) => {
  await selectTrip(page, seeded.threeInstallmentTripName);
  const receipt = "three-installments.png";
  await attachReceipt(page, receipt);
  await receiptAmount(page, receipt).fill("500");
  // #55 only requests the final allocation once the total is explicitly confirmed.
  await confirmTotal(page, "500.00", "ARS");
  await expect(page.getByText("Se imputa en #1, #2, #3", { exact: false })).toBeVisible();

  const registrationPromise = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/payments") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Enviar comprobante" }).click();
  const registration = await registrationPromise;
  expect(registration.status()).toBe(201);
  const submissionId = Number((await registration.json() as Record<string, unknown>).submissionId);

  const history = await getJson(api, "/api/v1/payments/my", seeded.accessToken) as Array<Record<string, unknown>>;
  const pending = history.find((payment) => payment.submissionId === submissionId);
  expect(pending).toMatchObject({ status: "PENDING", reportedAmount: "500.00", amountInTripCurrency: "500.00" });
  expect((pending?.installments as Array<Record<string, unknown>>).map((entry) => entry.amountInTripCurrency))
    .toEqual(["240.00", "240.00", "20.00"]);

  const adminPage = await context.newPage();
  await adminPage.addInitScript((accessToken) => {
    window.localStorage.setItem("pagos-viajes-auth-tokens", JSON.stringify({ accessToken, refreshToken: null }));
  }, seeded.adminAccessToken);
  await adminPage.goto("/payments/pending-review");
  const reviewCard = adminPage.locator("article").filter({ hasText: seeded.threeInstallmentTripName });
  await expect(reviewCard).toHaveCount(1);
  await openAmountReview(reviewCard);
  await expect(reviewCard.getByLabel("Monto a imputar")).toHaveValue("500.00");
  await reviewCard.getByRole("button", { name: "Guardar decisión" }).click();
  await expect(reviewCard).toHaveCount(0);

  const approved = (await getJson(api, "/api/v1/payments/my", seeded.accessToken) as Array<Record<string, unknown>>)
    .find((payment) => payment.submissionId === submissionId);
  expect(approved).toMatchObject({ status: "APPROVED", approvedAmount: "500.00", approvedAmountInTripCurrency: "500.00" });
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seeded.accessToken) as Array<Record<string, unknown>>)
    .filter((entry) => entry.tripId === seeded.threeInstallmentTripId)
    .sort((left, right) => Number(left.installmentNumber) - Number(right.installmentNumber));
  expect(installments.map((entry) => moneyToCents(entry.paidAmount)))
    .toEqual([24000n, 24000n, 2000n]);
  for (const [index, installment] of installments.entries()) {
    const entries = await getJson(
      api, `/api/v1/payments/installment/${String(installment.installmentId)}`, seeded.adminAccessToken,
    ) as Array<Record<string, unknown>>;
    expect(entries.find((entry) => entry.submissionId === submissionId)).toMatchObject({
      status: "APPROVED",
      amountInTripCurrency: ["240.00", "240.00", "20.00"][index],
    });
  }

  await adminPage.goto(`/trips/${seeded.threeInstallmentTripId}/spreadsheet`);
  const row = adminPage.locator("tbody tr").filter({ hasText: seeded.userEmail });
  await expect(row).toHaveCount(1);
  await row.locator("td").nth(1).click();
  const drawer = adminPage.getByRole("dialog");
  await expect(drawer).toBeVisible();
  await drawer.getByRole("button", { name: "Anular" }).click();
  await expect(drawer.getByRole("button", { name: "Anular" })).toHaveCount(0);

  const voided = (await getJson(api, "/api/v1/payments/my", seeded.accessToken) as Array<Record<string, unknown>>)
    .find((payment) => payment.submissionId === submissionId);
  expect(voided).toMatchObject({ status: "VOIDED" });
  expect(moneyToCents(voided?.approvedAmount)).toBe(0n);
  expect(moneyToCents(voided?.approvedAmountInTripCurrency)).toBe(0n);
  const afterVoid = (await getJson(api, "/api/v1/payments/my/installments", seeded.accessToken) as Array<Record<string, unknown>>)
    .filter((entry) => entry.tripId === seeded.threeInstallmentTripId);
  expect(afterVoid.map((entry) => moneyToCents(entry.paidAmount))).toEqual([0n, 0n, 0n]);
  for (const [index, installment] of installments.entries()) {
    const entries = await getJson(
      api, `/api/v1/payments/installment/${String(installment.installmentId)}`, seeded.adminAccessToken,
    ) as Array<Record<string, unknown>>;
    expect(entries.find((entry) => entry.submissionId === submissionId)).toMatchObject({
      status: "VOIDED",
      amountInTripCurrency: ["240.00", "240.00", "20.00"][index],
    });
  }
  expect(await readFxCalls()).toEqual([]);
  await adminPage.close();
});

test("approves an upward administrative correction from 240 reported to 300 credited", async ({ page, context }) => {
  await selectTrip(page, seeded.threeInstallmentTripName);
  const receipt = "upward-correction.png";
  await attachReceipt(page, receipt);
  await receiptAmount(page, receipt).fill("240");
  await confirmTotal(page, "240.00", "ARS");

  const registrationPromise = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/payments") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Enviar comprobante" }).click();
  const registration = await registrationPromise;
  expect(registration.status()).toBe(201);
  const submissionId = Number((await registration.json() as Record<string, unknown>).submissionId);
  expect(submissionId).toBeGreaterThan(0);

  const adminPage = await context.newPage();
  await adminPage.addInitScript((accessToken) => {
    window.localStorage.setItem("pagos-viajes-auth-tokens", JSON.stringify({ accessToken, refreshToken: null }));
  }, seeded.adminAccessToken);
  await adminPage.goto("/payments/pending-review");
  const reviewCard = adminPage.locator("article").filter({ hasText: seeded.threeInstallmentTripName });
  await expect(reviewCard).toHaveCount(1);
  await expect(reviewCard.getByText("Monto informado")).toBeVisible();
  await openAmountReview(reviewCard);
  await expect(reviewCard.getByLabel("Monto a imputar")).toHaveValue("240.00");
  await expect(reviewCard.getByText("Sin corrección")).toBeVisible();
  await reviewCard.getByLabel("Monto a imputar").fill("300");
  await expect(reviewCard.getByText("Corrección al alza")).toBeVisible();
  // The correction requires an observation before the decision can be saved.
  await expect(reviewCard.getByRole("button", { name: "Guardar decisión" })).toBeDisabled();
  await reviewCard.getByLabel(/Observación/).fill("El banco acreditó 300 en lugar de 240.");
  await reviewCard.getByRole("button", { name: "Guardar decisión" }).click();
  await expect(reviewCard).toHaveCount(0);

  const approved = (await getJson(api, "/api/v1/payments/my", seeded.accessToken) as Array<Record<string, unknown>>)
    .find((payment) => payment.submissionId === submissionId);
  expect(approved).toMatchObject({
    status: "APPROVED",
    reportedAmount: "240.00",
    approvedAmount: "300.00",
    rejectedAmount: "0.00",
  });
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seeded.accessToken) as Array<Record<string, unknown>>)
    .filter((entry) => entry.tripId === seeded.threeInstallmentTripId)
    .sort((left, right) => Number(left.installmentNumber) - Number(right.installmentNumber));
  expect(installments.map((entry) => moneyToCents(entry.paidAmount))).toEqual([24000n, 6000n, 0n]);

  await postJson(api, `/api/v1/payments/${submissionId}/void`, {}, seeded.adminAccessToken);
  const afterVoid = (await getJson(api, "/api/v1/payments/my/installments", seeded.accessToken) as Array<Record<string, unknown>>)
    .filter((entry) => entry.tripId === seeded.threeInstallmentTripId);
  expect(afterVoid.map((entry) => moneyToCents(entry.paidAmount))).toEqual([0n, 0n, 0n]);
  await adminPage.close();
});

test("CASE J conserves a partial cross-currency lifecycle across installments and void", async ({ page, context }) => {
  await selectTrip(page, seeded.caseJTripName);

  await page.getByLabel("Fecha de pago").fill(CASE_J_DATE);
  const receipt = "case-j-receipt.png";
  await attachReceipt(page, receipt);
  // A currency with no amount yet is not priced, so the quote is only requested once the
  // explicit amount exists.
  await receiptCurrency(page, receipt).selectOption("USD");
  await receiptAmount(page, receipt).fill("1.00");
  // The reported total stays in the receipts currency; the backend computes the
  // ARS equivalence authoritatively once the total is confirmed.
  await expect(page.getByText("Total en USD: 1.00")).toBeVisible();
  await confirmTotal(page, "1.00", "USD");
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE]);

  const registrationResponsePromise = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/payments") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Enviar comprobante" }).click();
  const registrationResponse = await registrationResponsePromise;
  expect(registrationResponse.status()).toBe(201);
  const registered = await registrationResponse.json() as Record<string, unknown>;
  const submissionId = Number(registered.submissionId);
  expect(submissionId).toBeGreaterThan(0);
  await expect(page.getByRole("heading", { name: "¡Pago reportado!" }),).toBeVisible();
  const pendingPayments = await getJson(api, "/api/v1/payments/my", seeded.accessToken) as Array<Record<string, unknown>>;
  const pendingPayment = pendingPayments.find((payment) => payment.submissionId === submissionId);
  if (!pendingPayment) {
    throw new Error("The submitted payment was not reloaded from the backend history.");
  }
  // The payment is reported in the receipts currency (USD); the trip-currency
  // equivalence is backend-authoritative and used only for imputation.
  expect(pendingPayment).toMatchObject({
    status: "PENDING",
    reportedAmount: "1.00",
    paymentCurrency: "USD",
    amountInTripCurrency: "1234.56",
    calculationVersion: "2",
  });
  expect(String(pendingPayment.exchangeRate)).toBe("1234.56");

  const pendingAllocations = pendingPayment.installments as Array<Record<string, unknown>>;
  expect(pendingAllocations.map((allocation) => Number(allocation.installmentNumber))).toEqual([1, 2]);
  expect(sumCents(pendingAllocations, "reportedAmount")).toBe(moneyToCents("1.00"));
  expect(sumCents(pendingAllocations, "amountInTripCurrency")).toBe(moneyToCents("1234.56"));

  const installmentsBeforeReview = await getJson(
    api,
    "/api/v1/payments/my/installments",
    seeded.accessToken,
  ) as Array<Record<string, unknown>>;
  const caseJInstallments = installmentsBeforeReview
    .filter((installment) => installment.tripId === seeded.caseJTripId)
    .sort((left, right) => Number(left.installmentNumber) - Number(right.installmentNumber));
  expect(caseJInstallments.map((installment) => Number(installment.installmentNumber))).toEqual([1, 2]);
  const firstInstallmentId = Number(caseJInstallments[0].installmentId);
  expect(caseJInstallments.map((installment) => moneyToCents(installment.paidAmount))).toEqual([0n, 0n]);

  const adminPage = await context.newPage();
  await adminPage.addInitScript((accessToken) => {
    window.localStorage.setItem("pagos-viajes-auth-tokens", JSON.stringify({ accessToken, refreshToken: null }));
  }, seeded.adminAccessToken);
  await adminPage.goto("/payments/pending-review");

  const reviewCard = adminPage.locator("article").filter({ hasText: seeded.caseJTripName });
  await expect(reviewCard).toHaveCount(1);
  await openAmountReview(reviewCard);
  await reviewCard.getByLabel("Monto a imputar").fill("0.50");
  await reviewCard.getByLabel(/Observación/).fill("Partial cross-currency test approval");
  await reviewCard.getByRole("button", { name: "Guardar decisión" }).click();
  await expect(reviewCard).toHaveCount(0);

  const partiallyReviewedPayments = await getJson(
    api,
    "/api/v1/payments/my",
    seeded.accessToken,
  ) as Array<Record<string, unknown>>;
  const partiallyReviewedPayment = partiallyReviewedPayments.find((payment) => payment.submissionId === submissionId);
  if (!partiallyReviewedPayment) {
    throw new Error("The partial review was not reloaded from the backend history.");
  }
  expect(partiallyReviewedPayment).toMatchObject({
    status: "PARTIALLY_APPROVED",
    reportedAmount: "1.00",
    approvedAmount: "0.50",
    rejectedAmount: "0.50",
    amountInTripCurrency: "1234.56",
    approvedAmountInTripCurrency: "617.28",
  });
  expect(
    moneyToCents(String(partiallyReviewedPayment.approvedAmount))
      + moneyToCents(String(partiallyReviewedPayment.rejectedAmount)),
  ).toBe(moneyToCents(String(partiallyReviewedPayment.reportedAmount)));

  const approvedAllocations = partiallyReviewedPayment.installments as Array<Record<string, unknown>>;
  expect(approvedAllocations).toHaveLength(1);
  expect(approvedAllocations[0]).toMatchObject({
    installmentId: firstInstallmentId,
    reportedAmount: "0.50",
    amountInTripCurrency: "617.28",
  });

  const installmentsAfterReview = await getJson(
    api,
    "/api/v1/payments/my/installments",
    seeded.accessToken,
  ) as Array<Record<string, unknown>>;
  const paidCaseJInstallments = installmentsAfterReview
    .filter((installment) => installment.tripId === seeded.caseJTripId)
    .sort((left, right) => Number(left.installmentNumber) - Number(right.installmentNumber));
  expect(paidCaseJInstallments.map((installment) => moneyToCents(installment.paidAmount))).toEqual([
    moneyToCents("617.28"),
    0n,
  ]);

  await adminPage.goto(`/trips/${seeded.caseJTripId}/spreadsheet`);
  const participantRow = adminPage.locator("tbody tr").filter({ hasText: seeded.userEmail });
  await expect(participantRow).toHaveCount(1);
  await participantRow.locator("td").nth(1).click();
  const paymentDrawer = adminPage.getByRole("dialog");
  await expect(paymentDrawer).toBeVisible();
  await paymentDrawer.getByRole("button", { name: "Anular" }).click();
  await expect(paymentDrawer.getByRole("button", { name: "Anular" })).toHaveCount(0);

  const paymentsAfterVoid = await getJson(api, "/api/v1/payments/my", seeded.accessToken) as Array<Record<string, unknown>>;
  const voidedPayment = paymentsAfterVoid.find((payment) => payment.submissionId === submissionId);
  if (!voidedPayment) {
    throw new Error("The voided payment was not reloaded from the backend history.");
  }
  expect(voidedPayment).toMatchObject({
    status: "VOIDED",
    reportedAmount: "1.00",
    rejectedAmount: "0.50",
  });
  expect(moneyToCents(voidedPayment.approvedAmount)).toBe(0n);
  expect(moneyToCents(voidedPayment.approvedAmountInTripCurrency)).toBe(0n);

  const installmentsAfterVoid = await getJson(
    api,
    "/api/v1/payments/my/installments",
    seeded.accessToken,
  ) as Array<Record<string, unknown>>;
  const voidedCaseJInstallments = installmentsAfterVoid
    .filter((installment) => installment.tripId === seeded.caseJTripId)
    .sort((left, right) => Number(left.installmentNumber) - Number(right.installmentNumber));
  expect(voidedCaseJInstallments.map((installment) => moneyToCents(installment.paidAmount))).toEqual([0n, 0n]);

  const firstInstallmentHistory = await getJson(
    api,
    `/api/v1/payments/installment/${firstInstallmentId}`,
    seeded.adminAccessToken,
  ) as Array<Record<string, unknown>>;
  const voidedAllocation = firstInstallmentHistory.find((item) => item.submissionId === submissionId);
  expect(voidedAllocation).toMatchObject({
    status: "VOIDED",
    reportedAmount: "0.50",
    paymentCurrency: "USD",
    amountInTripCurrency: "617.28",
  });
  expect(String(voidedAllocation?.exchangeRate)).toBe("1234.56");
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE]);
  await adminPage.close();
});

for (const scenario of [
  {
    name: "admin currency A approves an ARS submission in USD and voids without new FX",
    originalCurrency: "ARS", originalAmount: "306000.00", approvedCurrency: "USD",
    approvedAmount: "100.00", rejectedAmount: "153000.00", allocationAmounts: ["60.00", "40.00"],
    originalRate: "1530.00", adminRate: null,
  },
  {
    name: "admin currency B approves a USD submission in ARS using historical FX and voids exactly",
    originalCurrency: "USD", originalAmount: "200.00", approvedCurrency: "ARS",
    approvedAmount: "153000.00", rejectedAmount: "100.00", allocationAmounts: ["91800.00", "61200.00"],
    originalRate: null, adminRate: "1530.00",
  },
] as const) {
  test(scenario.name, async ({ page, context }) => {
    await selectTrip(page, seeded.adminCurrencyTripName);
    await page.getByLabel("Fecha de pago").fill(ADMIN_REVIEW_DATE);
    const receipt = `admin-currency-${scenario.originalCurrency}.png`;
    await attachReceipt(page, receipt);
    await receiptCurrency(page, receipt).selectOption(scenario.originalCurrency);
    await receiptAmount(page, receipt).fill(scenario.originalAmount);
    const submissionId = await submitAndCaptureForm(page, scenario.originalAmount, scenario.originalCurrency);
    const pending = await reloadedPayment(seeded.accessToken, submissionId);
    expect(pending).toMatchObject({
      status: "PENDING", reportedAmount: scenario.originalAmount, paymentCurrency: scenario.originalCurrency,
      amountInTripCurrency: "200.00", tripCurrency: "USD", exchangeRate: scenario.originalRate,
      reportedPaymentDate: ADMIN_REVIEW_DATE,
    });
    const original = await persistedReviewEvidence(submissionId);
    expect(original.outcomes).toEqual([]);
    const callsBeforeReview = scenario.originalRate ? [ADMIN_REVIEW_DATE] : [];
    expect(await readFxCalls()).toEqual(callsBeforeReview);
    const before = await adminCurrencyInstallments();
    expect(before.map((entry) => moneyToCents(entry.paidAmount))).toEqual([0n, 0n, 0n, 0n]);

    const adminPage = await context.newPage();
    await adminPage.addInitScript((accessToken) => {
      window.localStorage.setItem("pagos-viajes-auth-tokens", JSON.stringify({ accessToken, refreshToken: null }));
    }, seeded.adminAccessToken);
    await adminPage.goto("/payments/pending-review");
    const card = adminPage.locator("article").filter({ hasText: seeded.adminCurrencyTripName });
    await expect(card).toHaveCount(1);
    await openAmountReview(card);
    await expect(card.getByLabel("Moneda a imputar")).toHaveValue(scenario.originalCurrency);
    await card.getByLabel("Moneda a imputar").selectOption(scenario.approvedCurrency);
    await expect(card.getByLabel("Monto a imputar")).toHaveValue("");
    await expect(card.getByRole("slider")).toHaveCount(0);
    await card.getByLabel("Monto a imputar").fill(scenario.approvedAmount);
    await expect(card.getByRole("button", { name: "Guardar decisión" })).toBeDisabled();
    expect(await readFxCalls()).toEqual(callsBeforeReview);
    await card.getByLabel(/Observación/).fill("Independent currency E2E administrative decision");
    const reviewResponse = adminPage.waitForResponse((response) =>
      response.url().endsWith(`/api/v1/payments/${submissionId}/review`) && response.request().method() === "PATCH");
    await card.getByRole("button", { name: "Guardar decisión" }).click();
    const response = await reviewResponse;
    expect(response.status(), await response.text()).toBe(200);
    expect(response.request().postDataJSON()).toMatchObject({
      approvedCurrency: scenario.approvedCurrency, approvedAmount: scenario.approvedAmount,
    });
    await expect(card).toHaveCount(0);
    // A reuses original frozen evidence; B requests a quote only for the new administrative conversion.
    expect(await readFxCalls()).toEqual([ADMIN_REVIEW_DATE]);

    const approved = await reloadedPayment(seeded.accessToken, submissionId);
    expect(approved).toMatchObject({
      status: "PARTIALLY_APPROVED", reportedAmount: scenario.originalAmount,
      paymentCurrency: scenario.originalCurrency, exchangeRate: scenario.originalRate,
      amountInTripCurrency: "200.00", approvedCurrency: scenario.approvedCurrency,
      approvedAmount: scenario.approvedAmount, approvedAmountInTripCurrency: "100.00",
      rejectedCurrency: scenario.originalCurrency, rejectedAmount: scenario.rejectedAmount,
      approvedExchangeRate: scenario.adminRate,
      approvedQuoteRequestedDate: scenario.adminRate ? ADMIN_REVIEW_DATE : null,
      approvedQuoteEffectiveDate: scenario.adminRate ? ADMIN_REVIEW_DATE : null,
      approvedQuoteSource: scenario.adminRate ? "payment-fx-test" : null,
      approvedQuoteProvider: scenario.adminRate ? "deterministic-local-provider" : null,
      approvedQuoteProviderTimestamp: scenario.adminRate ? `${ADMIN_REVIEW_DATE}T12:00:00Z` : null,
      approvedCalculationVersion: "2",
    });
    const evidence = await persistedReviewEvidence(submissionId);
    expect(evidence.original).toEqual(original.original); // Every submission column except mutable status, plus attachments.
    const credited = evidence.outcomes.find((entry) => entry.status === "APPROVED")!;
    const rejected = evidence.outcomes.find((entry) => entry.status === "REJECTED")!;
    expect(evidence.outcomes).toHaveLength(2);
    expect(credited).toMatchObject({
      currency: scenario.approvedCurrency, reported_amount: scenario.approvedAmount,
      amount_in_trip_currency: "100.00", exchange_rate: scenario.adminRate ? "1530.00000000" : null,
      exchange_rate_scale: scenario.adminRate ? 2 : null,
      exchange_rate_requested_date: scenario.adminRate ? ADMIN_REVIEW_DATE : null,
      exchange_rate_effective_date: scenario.adminRate ? ADMIN_REVIEW_DATE : null,
      exchange_rate_source: scenario.adminRate ? "payment-fx-test" : null,
      exchange_rate_provider: scenario.adminRate ? "deterministic-local-provider" : null,
      exchange_rate_provider_timestamp: scenario.adminRate ? `${ADMIN_REVIEW_DATE}T12:00:00Z` : null,
      calculation_version: "2", resolved_by_email: adminEmail,
      admin_observation: "Independent currency E2E administrative decision",
    });
    expect(rejected).toMatchObject({
      currency: scenario.originalCurrency, reported_amount: scenario.rejectedAmount,
      amount_in_trip_currency: "100.00", exchange_rate: scenario.originalRate ? "1530.00000000" : null,
    });
    const originalSubmission = original.original.submission as Record<string, unknown>;
    for (const field of ["exchange_rate", "exchange_rate_scale", "exchange_rate_requested_date",
      "exchange_rate_effective_date", "exchange_rate_source", "exchange_rate_provider",
      "exchange_rate_provider_timestamp", "calculation_version"]) {
      expect(rejected[field], `Rejected snapshot ${field}`).toEqual(originalSubmission[field]);
    }
    expect(moneyToCents(rejected.reported_amount)).toBeLessThanOrEqual(moneyToCents(scenario.originalAmount));
    expect(moneyToCents(credited.amount_in_trip_currency) + moneyToCents(rejected.amount_in_trip_currency)).toBe(20000n);
    expect(evidence.allocations.map((entry) => entry.allocation_order)).toEqual([1, 2]);
    expect(evidence.allocations.map((entry) => entry.reported_amount)).toEqual(scenario.allocationAmounts);
    expect(evidence.allocations.map((entry) => entry.amount_in_trip_currency)).toEqual(["60.00", "40.00"]);
    expect(sumCents(evidence.allocations, "reported_amount")).toBe(moneyToCents(scenario.approvedAmount));
    expect(sumCents(evidence.allocations, "amount_in_trip_currency")).toBe(10000n);
    expect(sumCents(approved.installments, "reportedAmount")).toBe(moneyToCents(scenario.approvedAmount));
    expect(sumCents(approved.installments, "amountInTripCurrency")).toBe(10000n);
    expect(approved.installments.map((entry) => entry.allocationCurrency)).toEqual([scenario.approvedCurrency, scenario.approvedCurrency]);
    const installments = await adminCurrencyInstallments();
    expect(installments.map((entry) => moneyToCents(entry.paidAmount))).toEqual([6000n, 4000n, 0n, 0n]);
    const histories = [];
    for (const [index, installment] of installments.slice(0, 2).entries()) {
      const history = await getJson(api, `/api/v1/payments/installment/${String(installment.installmentId)}`,
        seeded.adminAccessToken) as Array<Record<string, unknown>>;
      const entry = history.find((item) => item.submissionId === submissionId)!;
      expect(entry).toMatchObject({
        status: "APPROVED", originalReportedAmount: scenario.originalAmount, paymentCurrency: scenario.originalCurrency,
        reportedAmount: scenario.allocationAmounts[index], allocationCurrency: scenario.approvedCurrency,
        amountInTripCurrency: ["60.00", "40.00"][index], allocationExchangeRate: scenario.adminRate,
        allocationQuoteRequestedDate: scenario.adminRate ? ADMIN_REVIEW_DATE : null,
        allocationQuoteEffectiveDate: scenario.adminRate ? ADMIN_REVIEW_DATE : null,
        allocationQuoteSource: scenario.adminRate ? "payment-fx-test" : null,
        allocationQuoteProvider: scenario.adminRate ? "deterministic-local-provider" : null,
        allocationQuoteProviderTimestamp: scenario.adminRate ? `${ADMIN_REVIEW_DATE}T12:00:00Z` : null,
        allocationCalculationVersion: "2",
      });
      histories.push(entry);
    }
    await adminPage.goto(`/trips/${seeded.adminCurrencyTripId}/spreadsheet`);
    const row = adminPage.locator("tbody tr").filter({ hasText: seeded.userEmail });
    await expect(row).toHaveCount(1);
    await row.locator("td").nth(1).click();
    const drawer = adminPage.getByRole("dialog");
    // The drawer also retains earlier voided history; inspect the one currently reversible credit.
    const activeHistory = drawer.getByRole("button", { name: "Anular", exact: true }).locator("..").locator("..");
    await expect(activeHistory).toHaveCount(1);
    await expect(activeHistory.getByText("Monto reportado:", { exact: true }).locator("..")).toHaveText(
      `Monto reportado: ${displayMoney(scenario.originalAmount, scenario.originalCurrency)}`);
    await expect(activeHistory.getByText("Monto acreditado asignado:", { exact: true }).locator("..")).toHaveText(
      `Monto acreditado asignado: ${displayMoney(scenario.allocationAmounts[0], scenario.approvedCurrency)}`);
    await expect(activeHistory.getByText("Equivalente imputado al viaje:", { exact: true }).locator("..")).toHaveText(
      `Equivalente imputado al viaje: ${displayMoney("60.00", "USD")}`);
    if (scenario.adminRate) {
      await expect(activeHistory.getByText("Cotización de la acreditación:", { exact: true }).locator("..")).toHaveText(
        "Cotización de la acreditación: 1530.00 ARS por USD · 03/09/2026");
    } else {
      await expect(activeHistory.getByText(/Cotización de la acreditación:/)).toHaveCount(0);
    }
    const voidResponse = adminPage.waitForResponse((candidate) =>
      candidate.url().endsWith(`/api/v1/payments/${submissionId}/void`) && candidate.request().method() === "POST");
    await drawer.getByRole("button", { name: "Anular", exact: true }).click();
    expect((await voidResponse).status()).toBe(200);
    await expect(drawer.getByRole("button", { name: "Anular", exact: true })).toHaveCount(0);
    const voided = await reloadedPayment(seeded.accessToken, submissionId);
    expect(voided).toMatchObject({ status: "VOIDED", approvedAmount: "0.00", approvedAmountInTripCurrency: "0.00",
      approvedCurrency: scenario.approvedCurrency, approvedExchangeRate: scenario.adminRate });
    expect((await adminCurrencyInstallments()).map((entry) => moneyToCents(entry.paidAmount))).toEqual([0n, 0n, 0n, 0n]);
    const afterVoid = await persistedReviewEvidence(submissionId);
    expect(afterVoid.original).toEqual(original.original);
    expect(afterVoid.allocations).toEqual(evidence.allocations); // No replanning or replacement of historical allocations.
    expect(afterVoid.outcomes.filter((entry) => entry.status !== "VOIDED")).toEqual(evidence.outcomes);
    const reversal = afterVoid.outcomes.find((entry) => entry.status === "VOIDED")!;
    const approvedSnapshot = Object.fromEntries(Object.entries(credited)
      .filter(([key]) => !["id", "status", "admin_observation", "resolved_at"].includes(key)));
    expect(reversal).toMatchObject(approvedSnapshot); // Includes both exact amounts and every frozen FX column.
    for (const [index, installment] of installments.slice(0, 2).entries()) {
      const history = await getJson(api, `/api/v1/payments/installment/${String(installment.installmentId)}`,
        seeded.adminAccessToken) as Array<Record<string, unknown>>;
      expect(history.find((item) => item.submissionId === submissionId)).toEqual({ ...histories[index], status: "VOIDED" });
    }
    expect(await readFxCalls()).toEqual([ADMIN_REVIEW_DATE]);
    await adminPage.close();
  });
}

function displayMoney(amount: string, currency: string) {
  // Display-only formatting; monetary assertions above use integer cents.
  return new Intl.NumberFormat("es-AR", { style: "currency", currency }).format(Number(amount));
}

async function adminCurrencyInstallments() {
  return (await getJson(api, "/api/v1/payments/my/installments", seeded.accessToken) as Array<Record<string, unknown>>)
    .filter((entry) => entry.tripId === seeded.adminCurrencyTripId)
    .sort((left, right) => Number(left.installmentNumber) - Number(right.installmentNumber));
}

async function persistedReviewEvidence(submissionId: number) {
  if (!Number.isSafeInteger(submissionId) || submissionId <= 0) throw new Error("Invalid submission id for read-only evidence");
  const { stdout } = await executeFile("docker", ["exec", requiredEnvironment("PAYMENT_E2E_POSTGRES_CONTAINER"),
    "psql", "-U", "payment_e2e", "-d", "payment_e2e", "-Atq", "-v", "ON_ERROR_STOP=1", "-c", `
      BEGIN READ ONLY;
      SELECT jsonb_build_object(
        'original', jsonb_build_object('submission', (to_jsonb(s) - 'status') || jsonb_build_object(
          'reported_amount', s.reported_amount::text, 'amount_in_trip_currency', s.amount_in_trip_currency::text,
          'exchange_rate', s.exchange_rate::text), 'attachments',
          (SELECT COALESCE(jsonb_agg(to_jsonb(a) ORDER BY a.id), '[]'::jsonb)
           FROM payment_submission_attachments a WHERE a.submission_id = s.id)),
        'outcomes', (SELECT COALESCE(jsonb_agg(to_jsonb(o) || jsonb_build_object(
          'reported_amount', o.reported_amount::text, 'amount_in_trip_currency', o.amount_in_trip_currency::text,
          'exchange_rate', o.exchange_rate::text) ORDER BY o.id), '[]'::jsonb)
          FROM payment_outcomes o WHERE o.submission_id = s.id),
        'allocations', (SELECT COALESCE(jsonb_agg(to_jsonb(a) || jsonb_build_object(
          'reported_amount', a.reported_amount::text, 'amount_in_trip_currency', a.amount_in_trip_currency::text)
          ORDER BY a.id), '[]'::jsonb) FROM payment_allocations a
          JOIN payment_outcomes o ON o.id = a.outcome_id WHERE o.submission_id = s.id))
      FROM payment_submissions s WHERE s.id = ${submissionId};
      COMMIT;`]);
  return JSON.parse(stdout.trim()) as {
    original: Record<string, unknown>; outcomes: Array<Record<string, unknown>>; allocations: Array<Record<string, unknown>>;
  };
}

function receiptAmount(page: Page, fileName: string) {
  return page.getByLabel(`Monto de ${fileName}`);
}

function receiptCurrency(page: Page, fileName: string) {
  return page.getByLabel(`Moneda de ${fileName}`);
}

/**
 * Receipt amounts and currencies are explicit, temporary user inputs, so a receipt must be
 * attached before any per-receipt control exists. The bytes are derived from the
 * file name so that two receipts in one submission are distinct files.
 */
async function attachReceipt(page: Page, fileName: string) {
  await page.locator('input[type="file"]').setInputFiles({
    name: fileName,
    mimeType: "image/png",
    buffer: Buffer.from(`synthetic payment receipt ${fileName}`),
  });
  await expect(receiptAmount(page, fileName)).toBeVisible();
}

/**
 * Opens the simplified amount review disclosed by PR #59.
 * Single place to update if the "Revisar monto" button name changes again.
 */
async function openAmountReview(reviewCard: Locator) {
  await reviewCard.getByRole("button", { name: "Revisar monto" }).click();
  await expect(reviewCard.getByLabel("Monto a imputar")).toBeVisible();
}

/** #55 requires an explicit total confirmation before the registration button unlocks. */
async function confirmTotal(page: Page, total: string, currency: string) {
  await page.getByRole("checkbox", { name: `Confirmo el total de ${total} ${currency} para estos comprobantes.` })
    .check();
  await expect(page.getByRole("button", { name: "Enviar comprobante" })).toBeEnabled();
}

/**
 * Submits the real UI form through the real backend and returns the created
 * submission id. Nothing is intercepted: the persisted state is re-read from
 * the backend afterwards.
 */
async function submitAndCaptureForm(page: Page, total: string, currency: string) {
  await confirmTotal(page, total, currency);
  const responsePromise = page.waitForResponse((candidate) =>
    candidate.url().endsWith("/api/v1/payments") && candidate.request().method() === "POST");
  await page.getByRole("button", { name: "Enviar comprobante" }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(201);
  const registered = await response.json() as Record<string, unknown>;
  const submissionId = Number(registered.submissionId);
  expect(submissionId).toBeGreaterThan(0);
  return submissionId;
}

/** Re-reads the submission from the backend so nothing is asserted from a UI echo. */
async function reloadedPayment(accessToken: string, submissionId: number) {
  const payment = (await getJson(api, "/api/v1/payments/my", accessToken) as Array<Record<string, unknown>>)
    .find((entry) => entry.submissionId === submissionId);
  if (!payment) {
    throw new Error(`Submission ${submissionId} was not reloaded from the backend history.`);
  }
  return payment as Record<string, unknown> & {
    installments: Array<Record<string, unknown>>;
    fileKeys: string[];
  };
}

async function selectTrip(page: import("@playwright/test").Page, tripName: string) {
  const select = page.getByLabel("Seleccioná el viaje");
  const option = select.locator("option").filter({ hasText: tripName });
  await expect(option).toHaveCount(1);

  const optionValue = await option.getAttribute("value");
  expect(optionValue).toBeTruthy();
  await select.selectOption(optionValue!);
  await expect(select).toHaveValue(/.+/);
}

async function readFxCalls() {
  const content = await readFile(fxCallLog, "utf8");
  return content.split(/\r?\n/).filter(Boolean);
}

async function getJson(context: APIRequestContext, path: string, token: string): Promise<unknown> {
  const response = await context.get(path, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `${path} returned HTTP ${response.status()}`).toBe(true);
  return await response.json() as unknown;
}

function moneyToCents(value: unknown): bigint {
  const decimal = String(value);
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(decimal);
  if (!match) {
    throw new Error(`Expected a non-negative money value, received ${decimal}`);
  }
  return BigInt(match[1]) * 100n + BigInt((match[2] ?? "").padEnd(2, "0"));
}

function sumCents(values: Array<Record<string, unknown>>, fieldName: string): bigint {
  return values.reduce((total, value) => total + moneyToCents(String(value[fieldName])), 0n);
}

async function seedPaymentContexts(context: APIRequestContext): Promise<SeededContext> {
  const stamp = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const digits = stamp.replace(/\D/g, "").slice(-8).padStart(8, "0");
  const studentDni = digits;
  const userEmail = `payment-e2e-${stamp}@example.com`;
  const userPassword = `Payment-E2e-${stamp}!`;

  const admin = await postJson(context, "/api/v1/auth/token", {
    email: adminEmail,
    password: adminPassword,
  });
  const adminToken = String(admin.accessToken);

  const trips = [
    { name: `CASE F ${stamp}`, amount: 20000, currency: "ARS" },
    { name: `CASE G ${stamp}`, amount: 0.01, currency: "USD" },
    { name: `Valid cents ${stamp}`, amount: 99.29, currency: "USD" },
  ] as const;

  for (const trip of trips) {
    const created = await postJson(context, "/api/v1/trips", {
      name: trip.name,
      totalAmount: trip.amount,
      firstInstallmentAmount: trip.amount,
      installmentsCount: 1,
      dueDay: 10,
      yellowWarningDays: 5,
      retroactiveActive: false,
      currency: trip.currency,
      firstDueDate: "2026-12-10",
      fixedFineAmount: 0,
    }, adminToken);
    await postJson(context, `/api/v1/trips/${String(created.id)}/users/bulk`, {
      studentDnis: [studentDni],
    }, adminToken);
  }

  const caseJTripName = `CASE J lifecycle ${stamp}`;
  const caseJTrip = await postJson(context, "/api/v1/trips", {
    name: caseJTripName,
    totalAmount: 1400,
    firstInstallmentAmount: 700,
    installmentsCount: 2,
    dueDay: 10,
    yellowWarningDays: 5,
    retroactiveActive: false,
    currency: "ARS",
    firstDueDate: "2026-12-10",
    fixedFineAmount: 0,
  }, adminToken);
  await postJson(context, `/api/v1/trips/${String(caseJTrip.id)}/users/bulk`, {
    studentDnis: [studentDni],
  }, adminToken);

  const threeInstallmentTripName = `Three installment lifecycle ${stamp}`;
  const threeInstallmentTrip = await postJson(context, "/api/v1/trips", {
    name: threeInstallmentTripName,
    totalAmount: 720,
    firstInstallmentAmount: 240,
    installmentsCount: 3,
    dueDay: 10,
    yellowWarningDays: 5,
    retroactiveActive: false,
    currency: "ARS",
    firstDueDate: "2026-12-10",
    fixedFineAmount: 0,
  }, adminToken);
  await postJson(context, `/api/v1/trips/${String(threeInstallmentTrip.id)}/users/bulk`, {
    studentDnis: [studentDni],
  }, adminToken);

  // Same-currency (ARS) and cross-currency (USD) destinations for the
  // multi-receipt submissions. Both leave balance headroom so the ARS 240000
  // total is always a valid manual payment.
  const multiReceiptArsTripName = `Multi receipt ARS ${stamp}`;
  const multiReceiptArsTrip = await postJson(context, "/api/v1/trips", {
    name: multiReceiptArsTripName,
    totalAmount: 300000,
    firstInstallmentAmount: 300000,
    installmentsCount: 1,
    dueDay: 10,
    yellowWarningDays: 5,
    retroactiveActive: false,
    currency: "ARS",
    firstDueDate: "2026-12-10",
    fixedFineAmount: 0,
  }, adminToken);
  await postJson(context, `/api/v1/trips/${String(multiReceiptArsTrip.id)}/users/bulk`, {
    studentDnis: [studentDni],
  }, adminToken);

  const multiReceiptUsdTrip = await postJson(context, "/api/v1/trips", {
    name: `Multi receipt USD ${stamp}`,
    totalAmount: 1000,
    firstInstallmentAmount: 1000,
    installmentsCount: 1,
    dueDay: 10,
    yellowWarningDays: 5,
    retroactiveActive: false,
    currency: "USD",
    firstDueDate: "2026-12-10",
    fixedFineAmount: 0,
  }, adminToken);
  await postJson(context, `/api/v1/trips/${String(multiReceiptUsdTrip.id)}/users/bulk`, {
    studentDnis: [studentDni],
  }, adminToken);

  const adminCurrencyTripName = `Admin currency USD ${stamp}`;
  const adminCurrencyTrip = await postJson(context, "/api/v1/trips", {
    name: adminCurrencyTripName, totalAmount: "240.00", firstInstallmentAmount: "60.00", installmentsCount: 4,
    dueDay: 10, yellowWarningDays: 5, retroactiveActive: false, currency: "USD",
    firstDueDate: "2026-12-10", fixedFineAmount: "0.00",
  }, adminToken);
  await postJson(context, `/api/v1/trips/${String(adminCurrencyTrip.id)}/users/bulk`, {
    studentDnis: [studentDni],
  }, adminToken);

  const signup = await postJson(context, "/api/v1/auth/signup", {
    email: userEmail,
    password: userPassword,
    name: "Payment",
    lastname: "E2E",
    dni: studentDni,
    phone: "123456789",
    students: [{ name: "Payment", lastname: "Student", dni: studentDni }],
  });

  for (const currency of ["ARS", "USD"] as const) {
    await postJson(context, "/api/v1/bank-accounts", {
      bankName: "Payment E2E Bank",
      accountLabel: `${currency} test account`,
      accountHolder: "Payment E2E",
      accountNumber: `${currency}-${stamp}`,
      taxId: "30-71131646-5",
      cbu: `${currency === "ARS" ? "1" : "2"}${digits.padStart(21, "0")}`.slice(0, 22),
      alias: `PAYMENT.${currency}.${digits}`,
      currency,
      displayOrder: 0,
    }, adminToken);
  }

  return {
    accessToken: String(signup.accessToken),
    adminAccessToken: adminToken,
    refreshToken: typeof signup.refreshToken === "string" ? signup.refreshToken : null,
    userEmail,
    caseFTripName: trips[0].name,
    caseGTripName: trips[1].name,
    validCentsTripName: trips[2].name,
    threeInstallmentTripName,
    threeInstallmentTripId: Number(threeInstallmentTrip.id),
    caseJTripName,
    caseJTripId: Number(caseJTrip.id),
    multiReceiptArsTripName,
    multiReceiptUsdTripName: `Multi receipt USD ${stamp}`,
    multiReceiptUsdTripId: Number(multiReceiptUsdTrip.id),
    adminCurrencyTripName,
    adminCurrencyTripId: Number(adminCurrencyTrip.id),
  };
}

async function postJson(
  context: APIRequestContext,
  path: string,
  body: Record<string, unknown>,
  token?: string,
) {
  const response = await context.post(path, {
    data: body,
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  expect(response.ok(), `${path} returned ${response.status()}: ${await response.text()}`).toBe(true);
  return await response.json() as Record<string, unknown>;
}

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is required for the isolated payment E2E`);
  }
  return value;
}
