import { defineConfig, devices } from "@playwright/test";

const frontendUrl = process.env.PAYMENT_E2E_FRONTEND_URL ?? "http://127.0.0.1:4173";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "line",
  use: {
    ...devices["Desktop Chrome"],
    baseURL: frontendUrl,
    trace: "retain-on-failure",
  },
});
