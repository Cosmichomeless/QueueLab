import fs from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import { API_URL } from "./stack";
import { pauseWorker, resumeWorker } from "./worker-control";

const SALES = "id,city,price\n1,Madrid,10.5\n2,Sevilla,\n3,\"Vigo, Galicia\",4\n";

async function openUploadForm(page: Page) {
  await page.goto("/");
  await expect(page).toHaveURL(/\/jobs$/);
  await expect(page.getByRole("heading", { name: "Trabajos" })).toBeVisible();
  await page.getByRole("main").getByRole("link", { name: "Nuevo CSV" }).click();
  await expect(page).toHaveURL(/\/jobs\/new$/);
  await expect(page.getByRole("heading", { name: "Nuevo trabajo CSV" })).toBeVisible();
}

/** Alertas del formulario (Next añade su propio `role="alert"` vacío para anunciar rutas, que no cuenta). */
function formAlert(page: Page) {
  return page.getByRole("region", { name: "Nuevo trabajo CSV" }).getByRole("alert");
}

async function chooseFile(page: Page, name: string, content: string | Buffer, mimeType = "text/csv") {
  await page.getByLabel("Fichero CSV").setInputFiles({ name, mimeType, buffer: Buffer.from(content) });
}

/** Sube el fichero y espera a estar en la página del trabajo; devuelve su id. */
async function submitAndFollow(page: Page): Promise<string> {
  await page.getByRole("button", { name: "Enviar" }).click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  return new URL(page.url()).pathname.split("/").pop()!;
}

// El worker congelado por un test no debe quedarse así si el test falla a medias.
test.afterEach(() => resumeWorker());

test.describe("trabajo CSV de principio a fin", () => {
  test("subida → cola → ejecución → resultado descargable", async ({ page }) => {
    const apiCalls: string[] = [];
    page.on("request", (request) => {
      if (request.url().startsWith(API_URL)) apiCalls.push(`${request.method()} ${new URL(request.url()).pathname}`);
    });

    // Con el worker congelado el trabajo tiene que quedarse esperando en la cola.
    pauseWorker();
    await openUploadForm(page);
    await chooseFile(page, "ventas.csv", SALES);
    await expect(page.getByText(/ventas\.csv · /)).toBeVisible();
    const id = await submitAndFollow(page);

    await expect(page.getByRole("heading", { name: `Trabajo ${id.slice(0, 8)}` })).toBeVisible();
    const status = page.locator("[data-status]");
    await expect(status).toHaveAttribute("data-status", "QUEUED");
    await expect(status).toHaveText("En cola");
    await expect(page.getByText("Esperando en la cola a que un worker lo recoja.")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Resultado" })).toHaveCount(0);

    // Al reanudar el worker, la página (que sondea sola) pasa a completado sin recargar.
    resumeWorker();
    await expect(status).toHaveAttribute("data-status", "COMPLETED");
    await expect(page.getByText("El trabajo ha terminado correctamente.")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Resultado" })).toBeVisible();
    await expect(page.getByText("CSV procesado: 3 filas, 3 columnas")).toBeVisible();

    const download = page.waitForEvent("download");
    await page.getByRole("link", { name: /^Descargar .*\.stats\.json$/ }).click();
    const file = await download;
    expect(file.suggestedFilename()).toBe(`${id}.stats.json`);
    const stats = JSON.parse(fs.readFileSync((await file.path())!, "utf8"));
    expect(stats.rows).toBe(3);
    expect(stats.columns.map((c: { name: string; type: string }) => `${c.name}:${c.type}`)).toEqual([
      "id:number",
      "city:text",
      "price:number",
    ]);

    // El trabajo aparece en el listado con su estado final.
    await page.getByRole("link", { name: "← Trabajos" }).click();
    const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: id.slice(0, 8) }) });
    await expect(row.getByText("Completado")).toBeVisible();
    await expect(row.getByText("csv-import")).toBeVisible();

    // El navegador habló con la API configurada en tiempo de ejecución, no con otra.
    expect(apiCalls).toContain("POST /api/v1/jobs/csv");
    expect(apiCalls).toContain(`GET /api/v1/jobs/${id}`);
    expect(apiCalls).toContain(`GET /api/v1/jobs/${id}/result`);
  });

  test("un CSV con una fila rota termina fallido y dice qué línea", async ({ page }) => {
    await openUploadForm(page);
    await chooseFile(page, "roto.csv", "id,city,price\n1,Madrid,10\n2,Sevilla\n");
    await submitAndFollow(page);

    const status = page.locator("[data-status]");
    await expect(status).toHaveAttribute("data-status", "FAILED");
    await expect(page.getByText("El trabajo ha fallado.")).toBeVisible();
    const failure = page.getByRole("region", { name: "Fallo" });
    await expect(failure).toContainText("Línea 3: tiene 2 campos y la cabecera 3");
    // Un trabajo fallido no anuncia resultado.
    await expect(page.getByRole("link", { name: /^Descargar / })).toHaveCount(0);
  });
});

test.describe("CSV inválido en la subida", () => {
  test("lo rechaza la API: el error se explica y no se crea ningún trabajo", async ({ page }) => {
    const created: string[] = [];
    page.on("response", (response) => {
      if (response.request().method() === "POST" && response.status() === 201) created.push(response.url());
    });
    await openUploadForm(page);

    await chooseFile(page, "en-blanco.csv", "   \n1,2\n");
    await page.getByRole("button", { name: "Enviar" }).click();
    await expect(formAlert(page)).toHaveText(
      "No se pudo enviar el fichero: La primera línea (cabecera) está vacía",
    );
    await expect(page).toHaveURL(/\/jobs\/new$/);

    // Se puede corregir y volver a intentarlo: el formulario no se queda bloqueado.
    await chooseFile(page, "no-utf8.csv", Buffer.from([0x61, 0xc3, 0x28, 0x2c, 0x62, 0x0a, 0x31, 0x2c, 0x32, 0x0a]));
    await expect(formAlert(page)).toHaveCount(0);
    await page.getByRole("button", { name: "Enviar" }).click();
    await expect(formAlert(page)).toHaveText("No se pudo enviar el fichero: El fichero no es UTF-8 válido");
    await expect(page).toHaveURL(/\/jobs\/new$/);

    expect(created).toEqual([]);
  });

  test("lo para el propio formulario antes de llamar a la API", async ({ page }) => {
    const uploads: string[] = [];
    page.on("request", (request) => {
      if (request.method() === "POST" && request.url().startsWith(API_URL)) uploads.push(request.url());
    });
    await openUploadForm(page);

    await chooseFile(page, "notas.txt", "hola", "text/plain");
    await expect(formAlert(page)).toHaveText("El fichero debe ser un CSV (extensión .csv o tipo text/csv).");
    await expect(page.getByRole("button", { name: "Enviar" })).toBeDisabled();

    await chooseFile(page, "vacio.csv", "");
    await expect(formAlert(page)).toHaveText("El fichero está vacío.");
    await expect(page.getByRole("button", { name: "Enviar" })).toBeDisabled();

    expect(uploads).toEqual([]);
  });
});
