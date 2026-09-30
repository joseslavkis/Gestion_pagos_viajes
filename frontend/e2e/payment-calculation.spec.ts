import { expect, request, test, type APIRequestContext, type Locator, type Page } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";

const apiUrl = requiredEnvironment("PAYMENT_E2E_API_URL");
const fxCallLog = requiredEnvironment("PAYMENT_E2E_FX_CALL_LOG");
const adminEmail = requiredEnvironment("PAYMENT_E2E_ADMIN_EMAIL");
const adminPassword = requiredEnvironment("PAYMENT_E2E_ADMIN_PASSWORD");

const CASE_F_FAST_DATE = "2026-01-15";
const CASE_F_SLOW_DATE = "2026-01-14";
const CASE_G_DATE = "2026-01-13";
const CASE_J_DATE = "2026-01-15";

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
};

let api: APIRequestContext;
let seeded: SeededContext;

test.describe.configure({ mode: "serial" });

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

test("CASE F keeps an explicit receipt amount through a fast ARS to USD to ARS toggle with one quote", async ({ page }) => {
  await selectTrip(page, seeded.caseFTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_F_FAST_DATE);

  const receipt = "case-f-fast.png";
  await attachReceipt(page, receipt);
  await receiptAmount(page, receipt).fill("10");
  await expect(page.getByText("Total en ARS: 10.00")).toBeVisible();
  // A receipt already denominated in the trip currency needs no quote at all.
  expect(await readFxCalls()).toEqual([]);

  // Moving the receipt to the opposite currency costs exactly one authoritative quote.
  await receiptCurrency(page, receipt).selectOption("USD");
  await expect.poll(readFxCalls).toEqual([CASE_F_FAST_DATE]);

  // Returning to the trip currency reuses that quote instead of pricing the same date twice.
  await receiptCurrency(page, receipt).selectOption("ARS");
  await expect(page.getByText("Total en ARS: 10.00")).toBeVisible();
  expect(await readFxCalls()).toEqual([CASE_F_FAST_DATE]);
});

test("CASE F ignores the delayed USD response after returning to ARS", async ({ page }) => {
  await selectTrip(page, seeded.caseFTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_F_SLOW_DATE);

  const receipt = "case-f-slow.png";
  await attachReceipt(page, receipt);
  await receiptAmount(page, receipt).fill("10");
  await expect(page.getByText("Total en ARS: 10.00")).toBeVisible();

  // Poll until the quote request is in flight: the provider sleeps 400ms before answering.
  await receiptCurrency(page, receipt).selectOption("USD");
  await expect.poll(readFxCalls).toEqual([CASE_F_SLOW_DATE]);
  await receiptCurrency(page, receipt).selectOption("ARS");

  await expect(page.getByText("Total en ARS: 10.00")).toBeVisible();
  // The abandoned USD quote lands after we are back on ARS; it must not replace the total.
  await page.waitForTimeout(600);
  await expect(page.getByText("Total en ARS: 10.00")).toBeVisible();
  expect(await readFxCalls()).toEqual([CASE_F_SLOW_DATE]);
});

test("CASE G prices an explicit amount authoritatively and keeps a valid cents balance without probes", async ({ page }) => {
  await selectTrip(page, seeded.caseGTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_G_DATE);

  // 10.16 ARS is the authoritative equivalent of this trip's 0.01 USD balance at 1015.50.
  // The conversion is asserted on the displayed trip-currency total, not on a rewritten input.
  const crossCurrency = "case-g-cross-currency.png";
  await attachReceipt(page, crossCurrency);
  await receiptCurrency(page, crossCurrency).selectOption("ARS");
  await receiptAmount(page, crossCurrency).fill("10.16");
  await expect(page.getByText("Total en USD: 0.01")).toBeVisible();
  await expect.poll(readFxCalls).toEqual([CASE_G_DATE]);

  // Drop the cross-currency receipt so the next trip is measured from a clean state.
  await page.getByRole("button", { name: `Quitar ${crossCurrency}` }).click();

  // A receipt already in the trip currency is priced without any extra quote, valid cents included.
  await selectTrip(page, seeded.validCentsTripName);
  const sameCurrency = "case-g-valid-cents.png";
  await attachReceipt(page, sameCurrency);
  await receiptAmount(page, sameCurrency).fill("99.29");
  await expect(page.getByText("Total en USD: 99.29")).toBeVisible();
  expect(await readFxCalls()).toEqual([CASE_G_DATE]);
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
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE]);
  await expect(page.getByText("Total en ARS: 1234.56")).toBeVisible();
  // Confirming a cross-currency total re-fetches the equivalence before granting the preview token.
  await confirmTotal(page, "1234.56", "ARS");
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE, CASE_J_DATE]);

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
  // #55 registers the payment in the trip currency using the backend-authoritative total.
  // The receipt's USD currency is a temporary input that only feeds that conversion.
  expect(pendingPayment).toMatchObject({
    status: "PENDING",
    reportedAmount: "1234.56",
    paymentCurrency: "ARS",
    exchangeRate: null,
    amountInTripCurrency: "1234.56",
    calculationVersion: "2",
  });

  const pendingAllocations = pendingPayment.installments as Array<Record<string, unknown>>;
  expect(pendingAllocations.map((allocation) => Number(allocation.installmentNumber))).toEqual([1, 2]);
  expect(sumCents(pendingAllocations, "reportedAmount")).toBe(moneyToCents("1234.56"));
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
    reportedAmount: "1234.56",
    approvedAmount: "0.50",
    rejectedAmount: "1234.06",
    amountInTripCurrency: "1234.56",
    approvedAmountInTripCurrency: "0.50",
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
    amountInTripCurrency: "0.50",
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
    moneyToCents("0.50"),
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
    reportedAmount: "1234.56",
    rejectedAmount: "1234.06",
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
    paymentCurrency: "ARS",
    exchangeRate: null,
    amountInTripCurrency: "0.50",
  });
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE, CASE_J_DATE]);
  await adminPage.close();
});

function receiptAmount(page: Page, fileName: string) {
  return page.getByLabel(`Monto de ${fileName}`);
}

function receiptCurrency(page: Page, fileName: string) {
  return page.getByLabel(`Moneda de ${fileName}`);
}

/**
 * Receipt amounts and currencies are explicit, temporary user inputs, so a receipt must be
 * attached before any per-receipt control exists.
 */
async function attachReceipt(page: Page, fileName: string) {
  await page.locator('input[type="file"]').setInputFiles({
    name: fileName,
    mimeType: "image/png",
    buffer: Buffer.from("synthetic payment receipt"),
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
