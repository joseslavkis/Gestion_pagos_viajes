import { expect, request, test, type APIRequestContext } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";

const apiUrl = requiredEnvironment("PAYMENT_E2E_API_URL");
const fxCallLog = requiredEnvironment("PAYMENT_E2E_FX_CALL_LOG");
const adminEmail = requiredEnvironment("PAYMENT_E2E_ADMIN_EMAIL");
const adminPassword = requiredEnvironment("PAYMENT_E2E_ADMIN_PASSWORD");

const CASE_F_FAST_DATE = "2026-01-15";
const CASE_F_SLOW_DATE = "2026-01-14";
const CASE_G_DATE = "2026-01-13";

type SeededContext = {
  accessToken: string;
  refreshToken: string | null;
  caseFTripName: string;
  caseGTripName: string;
  validCentsTripName: string;
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
    refreshToken: typeof signup.refreshToken === "string" ? signup.refreshToken : null,
    caseFTripName: trips[0].name,
    caseGTripName: trips[1].name,
    validCentsTripName: trips[2].name,
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
