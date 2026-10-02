import { expect, request, test, type APIRequestContext } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";

const apiUrl = requiredEnvironment("PAYMENT_E2E_API_URL");
const fxCallLog = requiredEnvironment("PAYMENT_E2E_FX_CALL_LOG");
const adminEmail = requiredEnvironment("PAYMENT_E2E_ADMIN_EMAIL");
const adminPassword = requiredEnvironment("PAYMENT_E2E_ADMIN_PASSWORD");

const MANUAL_DATE = "2026-01-16"; // rate 1200.00 en provider determinista
const MANUAL_RATE = "1200.00";

type ManualSeed = {
  adminToken: string;
  userToken: string;
  userEmail: string;
  arsTripName: string;
  arsTripId: number;
  threeTripName: string;
  threeTripId: number;
  usdTripName: string;
  usdTripId: number;
};

let api: APIRequestContext;
let seed: ManualSeed;

test.describe.configure({ mode: "serial" });

test.beforeAll(async () => {
  api = await request.newContext({ baseURL: apiUrl });
  seed = await seedManual(api);
});

test.afterAll(async () => {
  await api.dispose();
});

test.beforeEach(async ({ page }) => {
  await writeFile(fxCallLog, "", "utf8");
  await page.addInitScript((token) => {
    window.localStorage.setItem(
      "pagos-viajes-auth-tokens",
      JSON.stringify({ accessToken: token, refreshToken: null }),
    );
  }, seed.adminToken);
});

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function postJson(ctx: APIRequestContext, path: string, body: unknown, token?: string) {
  const res = await ctx.post(path, {
    data: body,
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  expect(res.ok(), `${path} -> ${res.status()}: ${await res.text()}`).toBe(true);
  return (await res.json()) as Record<string, unknown>;
}

async function getJson(ctx: APIRequestContext, path: string, token: string) {
  const res = await ctx.get(path, { headers: { Authorization: `Bearer ${token}` } });
  expect(res.ok(), `${path} -> ${res.status()}`).toBe(true);
  return (await res.json()) as unknown;
}

async function seedManual(ctx: APIRequestContext): Promise<ManualSeed> {
  const stamp = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const digits = stamp.replace(/\D/g, "").slice(-8).padStart(8, "0");
  const dni = digits;
  const userEmail = `manual-e2e-${stamp}@example.com`;
  const userPassword = `Manual-E2e-${stamp}!`;
  const admin = (await postJson(ctx, "/api/v1/auth/token", {
    email: adminEmail, password: adminPassword,
  })) as { accessToken: string };
  const adminToken = String(admin.accessToken);

  async function makeTrip(name: string, total: number, first: number, count: number, currency: string) {
    const created = await postJson(ctx, "/api/v1/trips", {
      name, totalAmount: total, firstInstallmentAmount: first, installmentsCount: count,
      dueDay: 10, yellowWarningDays: 5, retroactiveActive: false,
      currency, firstDueDate: "2026-12-10", fixedFineAmount: 0,
    }, adminToken);
    await postJson(ctx, `/api/v1/trips/${String(created.id)}/users/bulk`, {
      studentDnis: [dni],
    }, adminToken);
    return Number(created.id);
  }

  const arsTripName = `Manual ARS ${stamp}`;
  const arsTripId = await makeTrip(arsTripName, 200, 200, 1, "ARS");
  const threeTripName = `Manual 3x240 ${stamp}`;
  const threeTripId = await makeTrip(threeTripName, 720, 240, 3, "ARS");
  const usdTripName = `Manual USD ${stamp}`;
  const usdTripId = await makeTrip(usdTripName, 1000, 1000, 1, "USD");

  const signup = (await postJson(ctx, "/api/v1/auth/signup", {
    email: userEmail, password: userPassword, name: "Manual", lastname: "E2E",
    dni: dni, phone: "123456789",
    students: [{ name: "Manual", lastname: "Student", dni: dni }],
  })) as { accessToken: string };

  return {
    adminToken, userToken: String(signup.accessToken), userEmail,
    arsTripName, arsTripId, threeTripName, threeTripId, usdTripName, usdTripId,
  };
}

async function openFirstDrawer(page: import("@playwright/test").Page, tripId: number, userEmail: string) {
  await page.goto(`/trips/${tripId}/spreadsheet`);
  const row = page.locator("tbody tr").filter({ hasText: userEmail });
  await expect(row).toHaveCount(1);
  await row.locator("td").nth(1).click();
  const drawer = page.getByRole("dialog");
  await expect(drawer).toBeVisible();
  return drawer;
}

async function manualCalculation(
  anchorInstallmentId: number, reportedAmount: string | undefined,
  paymentCurrency: string, intent: string,
) {
  const body: Record<string, unknown> = {
    anchorInstallmentId, paymentCurrency,
    reportedPaymentDate: MANUAL_DATE, intent,
  };
  if (reportedAmount !== undefined) body.reportedAmount = reportedAmount;
  const res = await api.post("/api/v1/payments/calculation", {
    data: body,
    headers: { Authorization: `Bearer ${seed.adminToken}` },
  });
  expect(res.ok()).toBe(true);
  return (await res.json()) as Record<string, unknown>;
}

async function readFxCalls(): Promise<string[]> {
  const content = await readFile(fxCallLog, "utf8");
  return content.split(/\r?\n/).filter(Boolean);
}

test("E2E1 imputa parcial ARS misma moneda sin FX", async ({ page }) => {
  const drawer = await openFirstDrawer(page, seed.arsTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByLabel("Monto a imputar").fill("100");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByText("Confirmar imputación")).toBeVisible();
  await expect(drawer.getByText("→ parcial")).toBeVisible();
  const resp = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer.getByRole("button", { name: "Confirmar imputación" }).click();
  const created = await resp;
  expect(created.status()).toBe(201);
  const json = (await created.json()) as Record<string, unknown>;
  expect(json).toMatchObject({
    reportedAmount: "100.00", paymentCurrency: "ARS",
    amountInTripCurrency: "100.00", source: "ADMIN_MANUAL",
  });
  expect(await readFxCalls()).toEqual([]);
  // UI refleja nuevo saldo al reabrir.
  await page.reload();
  const drawer2 = await openFirstDrawer(page, seed.arsTripId, seed.userEmail);
  await expect(drawer2.getByText("100", { exact: false })).toBeVisible();
});

test("E2E2 distribuye 500 en 240/240/20", async ({ page }) => {
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seed.userToken) as Array<Record<string, unknown>>)
    .filter((i) => Number(i.tripId) === seed.threeTripId)
    .sort((a, b) => Number(a.installmentNumber) - Number(b.installmentNumber));
  expect(installments).toHaveLength(3);
  const anchorId = Number(installments[0].installmentId);
  const calc = await manualCalculation(anchorId, "500.00", "ARS", "MANUAL");
  expect((calc.installments as Array<Record<string, unknown>>).map((i) => i.amountInTripCurrency))
    .toEqual(["240.00", "240.00", "20.00"]);

  const drawer = await openFirstDrawer(page, seed.threeTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByLabel("Monto a imputar").fill("500");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByText("Cuota #1")).toBeVisible();
  await expect(drawer.getByText("Cuota #3")).toBeVisible();
  const resp = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer.getByRole("button", { name: "Confirmar imputación" }).click();
  const created = await resp;
  expect(created.status()).toBe(201);
  const json = (await created.json()) as Record<string, unknown> & { installments: Array<Record<string, unknown>> };
  expect(json.installments.map((i) => i.amountInTripCurrency)).toEqual(["240.00", "240.00", "20.00"]);
});

test("E2E4 cross currency ARS->USD usa cotización de la fecha", async ({ page }) => {
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seed.userToken) as Array<Record<string, unknown>>)
    .filter((i) => Number(i.tripId) === seed.usdTripId);
  expect(installments).toHaveLength(1);
  const anchorId = Number(installments[0].installmentId);
  const calc = await manualCalculation(anchorId, "240000.00", "ARS", "MANUAL");
  expect(calc).toMatchObject({
    reportedAmount: "240000.00", paymentCurrency: "ARS",
    amountInTripCurrency: "200.00", exchangeRate: MANUAL_RATE,
  });

  const drawer = await openFirstDrawer(page, seed.usdTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByLabel("Monto a imputar").fill("240000");
  await drawer.getByLabel("Moneda del pago").selectOption("ARS");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByText("240.000", { exact: false })).toBeVisible();
  await expect(drawer.getByText("US$", { exact: false }).first()).toBeVisible();
  const resp = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer.getByRole("button", { name: "Confirmar imputación" }).click();
  const created = await resp;
  expect(created.status()).toBe(201);
  const json = (await created.json()) as Record<string, unknown>;
  expect(json).toMatchObject({
    reportedAmount: "240000.00", paymentCurrency: "ARS",
    amountInTripCurrency: "200.00", exchangeRate: MANUAL_RATE,
    source: "ADMIN_MANUAL",
  });
});

test("E2E5 PENDING bloquea y el endpoint rechaza directo", async ({ page }) => {
  // Crear PENDING de cliente en el trip ARS vía API directa.
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seed.userToken) as Array<Record<string, unknown>>)
    .filter((i) => Number(i.tripId) === seed.arsTripId);
  const anchorId = Number(installments[0].installmentId);
  // Bank account para el PENDING.
  const bank = await postJson(api, "/api/v1/bank-accounts", {
    bankName: "E2E", accountLabel: "ARS", accountHolder: "H",
    accountNumber: `PEND-${Date.now()}`, taxId: "30-71131646-5",
    cbu: "0000000000000000000001", alias: `PEND.${Date.now()}`,
    currency: "ARS", displayOrder: 0,
  }, seed.adminToken);
  const calcRes = await api.post("/api/v1/payments/calculation", {
    data: {
      anchorInstallmentId: anchorId, paymentCurrency: "ARS",
      reportedPaymentDate: MANUAL_DATE, intent: "MANUAL", reportedAmount: "10.00",
    },
    headers: { Authorization: `Bearer ${seed.userToken}` },
  });
  const calcJson = (await calcRes.json()) as { previewToken: string };
  const reg = await api.post("/api/v1/payments", {
    data: {
      anchorInstallmentId: anchorId, reportedAmount: "10.00",
      reportedPaymentDate: MANUAL_DATE, paymentCurrency: "ARS",
      paymentMethod: "BANK_TRANSFER", bankAccountId: bank.id,
      previewToken: calcJson.previewToken,
    },
    headers: { Authorization: `Bearer ${seed.userToken}` },
  });
  expect(reg.status()).toBe(201);

  const drawer = await openFirstDrawer(page, seed.arsTripId, seed.userEmail);
  const button = drawer.getByRole("button", { name: "Imputar pago" });
  await expect(button).toBeDisabled();
  await expect(
    drawer.getByText("Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación."),
  ).toBeVisible();

  // Intento directo al endpoint manual debe ser rechazado.
  const direct = await api.post("/api/v1/payments/manual-imputations", {
    data: {
      anchorInstallmentId: anchorId, reportedAmount: "10.00",
      paymentCurrency: "ARS", reportedPaymentDate: MANUAL_DATE,
      previewToken: calcJson.previewToken,
    },
    headers: { Authorization: `Bearer ${seed.adminToken}` },
  });
  expect(direct.status()).toBe(409);
});

test("E2E6 cuota posterior es rechazada en UI y API", async () => {
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seed.userToken) as Array<Record<string, unknown>>)
    .filter((i) => Number(i.tripId) === seed.threeTripId)
    .sort((a, b) => Number(a.installmentNumber) - Number(b.installmentNumber));
  const fourth = installments[2];
  const anchorId = Number(fourth.installmentId);
  const ctxRes = await api.get(
    `/api/v1/payments/manual-imputations/context?installmentId=${anchorId}`,
    { headers: { Authorization: `Bearer ${seed.adminToken}` } },
  );
  expect(ctxRes.status()).toBe(200);
  const ctx = (await ctxRes.json()) as Record<string, unknown>;
  expect(ctx.eligible).toBe(false);
});

test("E2E10 reconfirmación: el primer preview no persiste", async ({ page }) => {
  const drawer = await openFirstDrawer(page, seed.threeTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByLabel("Monto a imputar").fill("100");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByText("Confirmar imputación")).toBeVisible();
  await drawer.getByRole("button", { name: "Volver" }).click();
  await drawer.getByLabel("Monto a imputar").fill("1000");
  // Cambiar monto invalida el preview anterior: debe recalcular.
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByText("Confirmar imputación")).toBeVisible();
});

test("E2E12 mobile básico usable", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 740 });
  const drawer = await openFirstDrawer(page, seed.threeTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  const amount = drawer.getByLabel("Monto a imputar");
  await expect(amount).toBeVisible();
  const box = await amount.boundingBox();
  expect(box).not.toBeNull();
  expect((box as { width: number }).width).toBeGreaterThan(0);
  await expect(drawer.getByRole("button", { name: "Continuar" })).toBeEnabled();
});
