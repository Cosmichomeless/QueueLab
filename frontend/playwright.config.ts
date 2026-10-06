import { defineConfig, devices } from "@playwright/test";
import { DASHBOARD_URL } from "./e2e/stack";

/**
 * Pruebas e2e del dashboard contra la pila real (PostgreSQL, RabbitMQ, API y worker): ver docs/e2e-dashboard.md.
 * `e2e/global-setup.ts` levanta y apaga la pila. Los tests comparten esa pila y un worker congelable, así que se
 * ejecutan de uno en uno.
 */
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  globalSetup: "./e2e/global-setup.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  outputDir: "./test-results",
  use: {
    baseURL: DASHBOARD_URL,
    locale: "es-ES",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
