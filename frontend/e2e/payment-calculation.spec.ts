import { expect, request, test, type APIRequestContext } from "@playwright/test";
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

test("CASE F keeps 20000 ARS through a fast ARS to USD to ARS toggle with one quote", async ({ page }) => {
  await selectTrip(page, seeded.caseFTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_F_FAST_DATE);

  const amount = page.getByLabel("Monto a reportar");
  const currency = page.getByLabel("Moneda en que pagaste");
  await expect(amount).toHaveJSProperty("valueAsNumber", 20000);

  await currency.selectOption("USD");
  await expect(amount).not.toHaveJSProperty("valueAsNumber", 20000);
  await currency.selectOption("ARS");

  await expect(amount).toHaveJSProperty("valueAsNumber", 20000);
  await expect.poll(readFxCalls).toEqual([CASE_F_FAST_DATE]);
});

test("CASE F ignores the delayed USD response after returning to ARS", async ({ page }) => {
  await selectTrip(page, seeded.caseFTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_F_SLOW_DATE);

  const amount = page.getByLabel("Monto a reportar");
  const currency = page.getByLabel("Moneda en que pagaste");
  await expect(amount).toHaveJSProperty("valueAsNumber", 20000);

  await currency.selectOption("USD");
  await currency.selectOption("ARS");

  await expect(amount).toHaveJSProperty("valueAsNumber", 20000);
  await expect.poll(readFxCalls).toEqual([CASE_F_SLOW_DATE]);
  await page.waitForTimeout(600);
  await expect(amount).toHaveJSProperty("valueAsNumber", 20000);
});

test("CASE G displays authoritative 10.16 and preserves a valid 99.29 balance without probes", async ({ page }) => {
  await selectTrip(page, seeded.caseGTripName);
  await page.getByLabel("Fecha de pago").fill(CASE_G_DATE);

  const amount = page.getByLabel("Monto a reportar");
  await expect(amount).toHaveJSProperty("valueAsNumber", 0.01);
  await page.getByLabel("Moneda en que pagaste").selectOption("ARS");
  await expect(amount).toHaveJSProperty("valueAsNumber", 10.16);
  await expect.poll(readFxCalls).toEqual([CASE_G_DATE]);

  await selectTrip(page, seeded.validCentsTripName);
  await expect(amount).toHaveJSProperty("valueAsNumber", 99.29);
  await expect.poll(readFxCalls).toEqual([CASE_G_DATE]);
});

test("CASE J conserves a partial cross-currency lifecycle across installments and void", async ({ page, context }) => {
  await selectTrip(page, seeded.caseJTripName);

  const amount = page.getByLabel("Monto a reportar");
  await page.getByLabel("Fecha de pago").fill(CASE_J_DATE);
  await page.getByLabel("Moneda en que pagaste").selectOption("USD");
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE]);
  await amount.fill("1.00");
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE, CASE_J_DATE]);
  await page.locator('input[type="file"]').setInputFiles({
    name: "case-j-receipt.png",
    mimeType: "image/png",
    buffer: Buffer.from("synthetic payment receipt"),
  });

  const registrationResponsePromise = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/payments") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Enviar comprobante" }).click();
  const registrationResponse = await registrationResponsePromise;
  expect(registrationResponse.status()).toBe(201);
  const registered = await registrationResponse.json() as Record<string, unknown>;
  const submissionId = Number(registered.submissionId);
  expect(submissionId).toBeGreaterThan(0);
  await expect(page.getByRole("heading", { name: "¡Comprobante adjuntado!" })).toBeVisible();

  const pendingPayments = await getJson(api, "/api/v1/payments/my", seeded.accessToken) as Array<Record<string, unknown>>;
  const pendingPayment = pendingPayments.find((payment) => payment.submissionId === submissionId);
  if (!pendingPayment) {
    throw new Error("The submitted payment was not reloaded from the backend history.");
  }
  expect(pendingPayment).toMatchObject({
    status: "PENDING",
    reportedAmount: "1.00",
    paymentCurrency: "USD",
    exchangeRate: "1234.56",
    amountInTripCurrency: "1234.56",
    calculationVersion: "3",
  });

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
  await reviewCard.getByRole("button", { name: "Ver imputación y decidir" }).click();
  await reviewCard.getByLabel("Monto a aprobar").fill("0.50");
  await reviewCard.getByLabel("Observación admin").fill("Partial cross-currency test approval");
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
    exchangeRate: "1234.56",
    amountInTripCurrency: "617.28",
  });
  await expect.poll(readFxCalls).toEqual([CASE_J_DATE, CASE_J_DATE]);
  await adminPage.close();
});

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
