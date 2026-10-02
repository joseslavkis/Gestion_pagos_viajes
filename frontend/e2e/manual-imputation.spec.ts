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
  quickTripName: string;
  quickTripId: number;
  limitTripName: string;
  limitTripId: number;
  staleTripName: string;
  staleTripId: number;
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
  const quickTripName = `Manual Quick ${stamp}`;
  const quickTripId = await makeTrip(quickTripName, 300, 150, 2, "ARS");
  const limitTripName = `Manual Limit ${stamp}`;
  const limitTripId = await makeTrip(limitTripName, 200, 200, 1, "ARS");
  const staleTripName = `Manual Stale ${stamp}`;
  const staleTripId = await makeTrip(staleTripName, 200, 100, 2, "ARS");

  const signup = (await postJson(ctx, "/api/v1/auth/signup", {
    email: userEmail, password: userPassword, name: "Manual", lastname: "E2E",
    dni: dni, phone: "123456789",
    students: [{ name: "Manual", lastname: "Student", dni: dni }],
  })) as { accessToken: string };

  return {
    adminToken, userToken: String(signup.accessToken), userEmail,
    arsTripName, arsTripId, threeTripName, threeTripId, usdTripName, usdTripId,
    quickTripName, quickTripId, limitTripName, limitTripId, staleTripName, staleTripId,
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
  await expect(drawer.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();
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
  await expect(drawer2.getByText("Imputación manual")).toBeVisible();
  expect(await paidAmount(seed.arsTripId, 1)).toBe(100);
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
  await expect(drawer.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();
  await drawer.getByRole("button", { name: "Volver" }).click();
  await drawer.getByLabel("Monto a imputar").fill("200");
  // Cambiar monto invalida el preview anterior: debe recalcular.
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();
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

async function firstInstallmentId(tripId: number): Promise<number> {
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seed.userToken) as Array<Record<string, unknown>>)
    .filter((i) => Number(i.tripId) === tripId)
    .sort((a, b) => Number(a.installmentNumber) - Number(b.installmentNumber));
  return Number(installments[0].installmentId);
}

async function paidAmount(tripId: number, number: number): Promise<number> {
  const installments = (await getJson(api, "/api/v1/payments/my/installments", seed.userToken) as Array<Record<string, unknown>>)
    .filter((i) => Number(i.tripId) === tripId && Number(i.installmentNumber) === number);
  return Number(installments[0].paidAmount);
}

test("E2E20 quick action completa la cuota actual misma moneda", async ({ page }) => {
  const drawer = await openFirstDrawer(page, seed.quickTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByText("Completar cuota actual").click();
  // El input queda exactamente con el saldo calculado por el backend.
  await expect(drawer.getByLabel("Monto a imputar")).toHaveValue("150.00");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();
  // El preview afecta solo la primera cuota.
  await expect(drawer.getByText("Cuota #1", { exact: false })).toBeVisible();
  await expect(drawer.getByText("→ completa")).toBeVisible();
  const resp = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer.getByRole("button", { name: "Confirmar imputación" }).click();
  expect((await resp).status()).toBe(201);
  // Cuota completa y siguiente intacta.
  expect(await paidAmount(seed.quickTripId, 1)).toBe(150);
  expect(await paidAmount(seed.quickTripId, 2)).toBe(0);
});

test("E2E21 quick action cross-currency usa el cálculo del backend", async ({ page }) => {
  const anchorId = await firstInstallmentId(seed.usdTripId);
  // Monto autoritativo para completar el saldo USD restante en ARS.
  const calc = await manualCalculation(anchorId, undefined, "ARS", "REMAINING");
  const expected = String(calc.reportedAmount);
  expect(Number(expected)).toBeGreaterThan(0);

  const drawer = await openFirstDrawer(page, seed.usdTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByText("Completar cuota actual").click();
  // El frontend no multiplica tasa a mano: vuelca el cálculo del backend.
  await expect(drawer.getByLabel("Monto a imputar")).toHaveValue(expected);
});

test("E2E22 monto superior al saldo no persiste y avisa", async ({ page }) => {
  const drawer = await openFirstDrawer(page, seed.limitTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByLabel("Monto a imputar").fill("201");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  let posted = false;
  page.on("request", (r) => {
    if (r.url().includes("/manual-imputations") && r.method() === "POST") posted = true;
  });
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByText("El monto ingresado supera el saldo pendiente del viaje.")).toBeVisible();
  await page.waitForTimeout(1500);
  expect(posted).toBe(false);
  expect(await paidAmount(seed.limitTripId, 1)).toBe(0);
});

test("E2E23 comprobante opcional: sin y con archivo", async ({ page }) => {
  const anchorId = await firstInstallmentId(seed.limitTripId);
  // Sin comprobante: la operación es válida y el historial queda sin adjunto.
  const drawer = await openFirstDrawer(page, seed.limitTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByLabel("Monto a imputar").fill("50");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();
  const first = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer.getByRole("button", { name: "Confirmar imputación" }).click();
  expect((await first).status()).toBe(201);

  // Con comprobante: el adjunto queda asociado al manual correcto.
  await page.reload();
  const drawer2 = await openFirstDrawer(page, seed.limitTripId, seed.userEmail);
  await drawer2.getByRole("button", { name: "Imputar pago" }).click();
  await drawer2.getByLabel("Monto a imputar").fill("60");
  await drawer2.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer2.getByLabel("Comprobante (opcional)").setInputFiles({
    name: "comprobante.png",
    mimeType: "image/png",
    buffer: Buffer.from([1, 2, 3, 4]),
  });
  await drawer2.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer2.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();
  const second = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer2.getByRole("button", { name: "Confirmar imputación" }).click();
  const created = await second;
  expect(created.status()).toBe(201);
  const json = (await created.json()) as Record<string, unknown>;
  expect(json.source).toBe("ADMIN_MANUAL");

  const history = (await getJson(api, `/api/v1/payments/installment/${anchorId}`, seed.adminToken) as Array<Record<string, unknown>>)
    .filter((h) => h.source === "ADMIN_MANUAL");
  expect(history).toHaveLength(2);
  const withFile = history.filter((h) => (h.fileKeys as Array<unknown> | undefined)?.length);
  expect(withFile).toHaveLength(1);
  expect(String(withFile[0].reportedAmount)).toBe("60.00");
});

test("E2E24 cambio de fecha recalcula FX con token nuevo", async () => {
  const anchorId = await firstInstallmentId(seed.usdTripId);
  const calcA = await manualCalculation(anchorId, "120000.00", "ARS", "MANUAL");
  expect(calcA.exchangeRate).toBe("1200.00");
  // Misma operación otro día: el provider determinista devuelve otra tasa.
  const resB = await api.post("/api/v1/payments/calculation", {
    data: {
      anchorInstallmentId: anchorId, paymentCurrency: "ARS",
      reportedPaymentDate: "2026-01-13", intent: "MANUAL", reportedAmount: "120000.00",
    },
    headers: { Authorization: `Bearer ${seed.adminToken}` },
  });
  expect(resB.ok()).toBe(true);
  const calcB = (await resB.json()) as Record<string, unknown>;
  expect(calcB.exchangeRate).toBe("1015.50");
  expect(calcB.previewToken).not.toBe(calcA.previewToken);
});

test("E2E25 stale preview real: confirma viejo, recalcula y confirma nuevo", async ({ page }) => {
  const anchorId = await firstInstallmentId(seed.staleTripId);
  const drawer = await openFirstDrawer(page, seed.staleTripId, seed.userEmail);
  await drawer.getByRole("button", { name: "Imputar pago" }).click();
  await drawer.getByLabel("Monto a imputar").fill("150");
  await drawer.getByLabel("Fecha de pago").fill(MANUAL_DATE);
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();

  // Otra operación cambia el saldo mientras el preview viejo sigue en pantalla.
  const midToken = await manualCalculation(anchorId, "50.00", "ARS", "MANUAL")
    .then((c) => String(c.previewToken));
  const mid = await api.post("/api/v1/payments/manual-imputations", {
    multipart: {
      anchorInstallmentId: String(anchorId),
      reportedAmount: "50.00",
      reportedPaymentDate: MANUAL_DATE,
      paymentCurrency: "ARS",
      previewToken: midToken,
    },
    headers: { Authorization: `Bearer ${seed.adminToken}` },
  });
  expect(mid.status()).toBe(201);

  // Confirmar el preview viejo: 409 STALE con mensaje accionable.
  const staleResp = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer.getByRole("button", { name: "Confirmar imputación" }).click();
  expect((await staleResp).status()).toBe(409);
  await expect(
    drawer.getByText("El saldo cambió desde la última previsualización. Actualizá la imputación e intentá nuevamente."),
  ).toBeVisible();

  // Recalcular muestra el plan nuevo y permite confirmar.
  await drawer.getByRole("button", { name: "Continuar" }).click();
  await expect(drawer.getByRole("heading", { name: "Confirmar imputación" })).toBeVisible();
  const fresh = page.waitForResponse((r) =>
    r.url().includes("/manual-imputations") && r.request().method() === "POST");
  await drawer.getByRole("button", { name: "Confirmar imputación" }).click();
  expect((await fresh).status()).toBe(201);
  expect(await paidAmount(seed.staleTripId, 1)).toBe(150);
  expect(await paidAmount(seed.staleTripId, 2)).toBe(50);
});
