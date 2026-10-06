// Genera las capturas de docs/screenshots/ contra el stack de Compose ya levantado (ver scripts/screenshots.sh).
// Siembra los datos por la API (CSV deterministas, dominio example.com), pausa el worker para fotografiar un trabajo en
// cola y vuelve a reanudarlo. Uso: node frontend/scripts/screenshots.mjs [directorio de salida]
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs", "screenshots"));
const DASHBOARD = process.env.DASHBOARD_URL ?? "http://127.0.0.1:3000";
const API = process.env.API_URL ?? "http://127.0.0.1:8080";
const PROJECT = process.env.COMPOSE_PROJECT_NAME ?? "queuelab-shots";

const log = (message) => console.log(`[capturas] ${message}`);

function compose(...args) {
  execFileSync("docker", ["compose", "-p", PROJECT, ...args], { cwd: ROOT, stdio: "ignore" });
}

// Generador pseudoaleatorio fijo: los mismos CSV en cada ejecución.
function rng(seed) {
  let state = seed;
  return () => {
    state = (state * 1664525 + 1013904223) % 4294967296;
    return state / 4294967296;
  };
}

const NOMBRES = ["Lucía", "Mateo", "Sofía", "Daniel", "Valeria", "Hugo", "Martina", "Pablo", "Carla", "Álvaro"];
const PAISES = ["ES", "MX", "AR", "CO", "CL", "PE"];

function clientesCsv(rows, seed) {
  const random = rng(seed);
  const lines = ["id,nombre,email,pais,importe"];
  for (let i = 1; i <= rows; i++) {
    const nombre = NOMBRES[Math.floor(random() * NOMBRES.length)];
    const pais = PAISES[Math.floor(random() * PAISES.length)];
    const importe = (random() * 900 + 10).toFixed(2);
    lines.push(`${i},${nombre},cliente${i}@example.com,${pais},${importe}`);
  }
  return lines.join("\n") + "\n";
}

async function upload(name, csv) {
  const body = new FormData();
  body.append("file", new Blob([csv], { type: "text/csv" }), name);
  const response = await fetch(`${API}/api/v1/jobs/csv`, { method: "POST", body });
  if (response.status !== 201) throw new Error(`Subida de ${name}: HTTP ${response.status}`);
  return (await response.json()).id;
}

async function waitFor(id, statuses) {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const job = await (await fetch(`${API}/api/v1/jobs/${id}`)).json();
    if (statuses.includes(job.status)) return job;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`El trabajo ${id} no llegó a ${statuses.join("/")} en 60 s`);
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });

  // 1. Datos: tres trabajos completados y uno fallido, y uno en cola con el worker pausado.
  log("sembrando trabajos…");
  const completed = [];
  for (const [name, rows, seed] of [
    ["clientes-2026-q1.csv", 1200, 11],
    ["clientes-2026-q2.csv", 1500, 22],
    ["clientes-2026-q3.csv", 1800, 33],
  ]) {
    const id = await upload(name, clientesCsv(rows, seed));
    await waitFor(id, ["COMPLETED"]);
    completed.push(id);
  }
  const broken = clientesCsv(300, 44).replace("cliente150@example.com,", "");
  const failedId = await upload("clientes-incompleto.csv", broken);
  await waitFor(failedId, ["FAILED"]);

  const browser = await chromium.launch();
  try {
    const desktop = await browser.newContext({ viewport: { width: 1100, height: 720 }, locale: "es-ES" });
    const page = await desktop.newPage();

    // 2. Formulario con un fichero elegido.
    log("formulario…");
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "queuelab-shots-"));
    const named = path.join(tmp, "clientes-2026-q4.csv");
    fs.writeFileSync(named, clientesCsv(2000, 55));
    await page.goto(`${DASHBOARD}/jobs/new`);
    await page.getByLabel("Fichero CSV").setInputFiles(named);
    await page.getByText("clientes-2026-q4.csv ·").waitFor();
    await page.screenshot({ path: path.join(OUT, "02-nuevo-csv.png") });
    fs.rmSync(tmp, { recursive: true });

    // 3. Trabajo en cola: con el worker pausado nadie lo recoge.
    log("trabajo en cola…");
    compose("pause", "worker");
    let queuedId;
    try {
      queuedId = await upload("clientes-2026-q4.csv", clientesCsv(2000, 55));
      await page.goto(`${DASHBOARD}/jobs/${queuedId}`);
      await page.locator("[data-status='QUEUED']").first().waitFor();
      await page.screenshot({ path: path.join(OUT, "03-detalle-en-cola.png") });
    } finally {
      compose("unpause", "worker");
    }
    await waitFor(queuedId, ["COMPLETED"]);

    // 4. Detalle completado y fallido.
    log("detalle completado y fallido…");
    await page.goto(`${DASHBOARD}/jobs/${completed[2]}`);
    await page.getByRole("heading", { name: "Resultado" }).waitFor();
    await page.screenshot({ path: path.join(OUT, "04-detalle-completado.png") });
    await page.goto(`${DASHBOARD}/jobs/${failedId}`);
    await page.getByRole("heading", { name: "Fallo" }).waitFor();
    await page.screenshot({ path: path.join(OUT, "05-detalle-fallido.png") });

    // 5. Lista (escritorio) y detalle en móvil (la tabla de la lista se recorta en 390 px: ver README, limitaciones).
    log("lista…");
    await page.goto(`${DASHBOARD}/jobs`);
    await page.locator("[data-status]").first().waitFor();
    await page.screenshot({ path: path.join(OUT, "01-trabajos.png") });
    const mobile = await browser.newContext({ viewport: { width: 390, height: 780 }, deviceScaleFactor: 2, isMobile: true, locale: "es-ES" });
    const phone = await mobile.newPage();
    await phone.goto(`${DASHBOARD}/jobs/${completed[2]}`);
    await phone.getByRole("heading", { name: "Resultado" }).waitFor();
    await phone.screenshot({ path: path.join(OUT, "06-movil.png") });
  } finally {
    await browser.close();
  }
  log(`listo: ${OUT}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
