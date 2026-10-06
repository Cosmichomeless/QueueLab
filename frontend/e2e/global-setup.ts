import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import {
  API_URL,
  BACKEND_DIR,
  DASHBOARD_IS_EXTERNAL,
  DASHBOARD_URL,
  FRONTEND_DIR,
  OUT_DIR,
  PORTS,
  WORKER_PID_FILE,
  apiEnv,
  workerEnv,
} from "./stack";

/**
 * Levanta la pila completa desde cero y devuelve la función que la apaga:
 * PostgreSQL y RabbitMQ (docker compose) → jars de la API y del worker → dashboard (`next start`).
 * Con `E2E_SKIP_BUILD=1` no se recompila nada (útil al repetir la ejecución); con `E2E_DASHBOARD_URL` se prueba un
 * dashboard que ya está en marcha (p. ej. el contenedor) en vez de arrancar uno.
 */
const COMPOSE_FILE = path.join(FRONTEND_DIR, "e2e", "compose.yml");
const skipBuild = process.env.E2E_SKIP_BUILD === "1";

const children: ChildProcess[] = [];

function log(message: string) {
  console.log(`[e2e] ${message}`);
}

function run(command: string, args: string[], options: { cwd: string; env?: Record<string, string> }) {
  execFileSync(command, args, { cwd: options.cwd, env: { ...process.env, ...options.env }, stdio: "inherit" });
}

function findJar(module: string): string {
  const target = path.join(BACKEND_DIR, module, "target");
  const jar = fs.existsSync(target)
    ? fs.readdirSync(target).find((f) => new RegExp(`^queuelab-${module}-.*\\.jar$`).test(f) && !f.endsWith(".original"))
    : undefined;
  if (!jar) {
    throw new Error(`No hay jar de ${module} en ${target}: quita E2E_SKIP_BUILD o ejecuta ./mvnw -pl api,worker -am package en backend/`);
  }
  return path.join(target, jar);
}

function launch(name: string, command: string, args: string[], cwd: string, env: Record<string, string>): ChildProcess {
  const out = fs.openSync(path.join(OUT_DIR, `${name}.log`), "w");
  const child = spawn(command, args, { cwd, env: { ...process.env, ...env }, stdio: ["ignore", out, out] });
  child.on("exit", (code, signal) => {
    if (!stopping) console.error(`[e2e] ${name} terminó inesperadamente (código ${code}, señal ${signal}); ver e2e/.out/${name}.log`);
  });
  children.push(child);
  return child;
}

let stopping = false;

async function waitFor(name: string, url: string, timeoutMs: number) {
  const deadline = Date.now() + timeoutMs;
  let last = "sin respuesta";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
      last = `HTTP ${response.status}`;
    } catch (error) {
      last = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`${name} no respondió en ${timeoutMs / 1000} s en ${url} (${last}); ver e2e/.out/${name}.log`);
}

async function assertFree(name: string, url: string) {
  const busy = await fetch(url).then(
    () => true,
    () => false,
  );
  if (busy) throw new Error(`${name}: ya hay algo escuchando en ${url}; las e2e necesitan ese puerto libre`);
}

async function stop() {
  stopping = true;
  for (const child of children.reverse()) {
    if (child.exitCode !== null) continue;
    // Un proceso detenido (SIGSTOP, ver worker-control.ts) no atiende SIGTERM hasta que se reanuda.
    child.kill("SIGCONT");
    child.kill("SIGTERM");
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        resolve();
      }, 15_000);
      child.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
  try {
    run("docker", ["compose", "-f", COMPOSE_FILE, "down", "-v", "--remove-orphans"], { cwd: FRONTEND_DIR });
  } catch (error) {
    console.error("[e2e] no se pudo apagar la infraestructura (docker compose down):", error);
  }
}

export default async function globalSetup() {
  // Cada ejecución parte de cero: sin ficheros ni logs de la anterior.
  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });

  await assertFree("API", `${API_URL}/actuator/health`);
  if (!DASHBOARD_IS_EXTERNAL) await assertFree("Dashboard", DASHBOARD_URL);

  try {
    log("infraestructura (PostgreSQL + RabbitMQ)…");
    run("docker", ["compose", "-f", COMPOSE_FILE, "up", "-d", "--wait"], { cwd: FRONTEND_DIR });

    if (!skipBuild) {
      log("empaquetando API y worker…");
      run("./mvnw", ["-q", "-DskipTests", "-pl", "api,worker", "-am", "package"], { cwd: BACKEND_DIR });
    }

    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";

    log("API…");
    launch("api", java, ["-jar", findJar("api")], BACKEND_DIR, apiEnv());
    await waitFor("api", `${API_URL}/actuator/health`, 120_000);

    log("worker…");
    const worker = launch("worker", java, ["-jar", findJar("worker")], BACKEND_DIR, workerEnv());
    fs.writeFileSync(WORKER_PID_FILE, String(worker.pid));
    await waitFor("worker", `http://localhost:${PORTS.workerMetrics}/metrics`, 120_000);

    if (!DASHBOARD_IS_EXTERNAL) {
      if (!skipBuild) {
        log("compilando el dashboard…");
        run("npm", ["run", "build"], { cwd: FRONTEND_DIR, env: { QUEUELAB_API_URL: API_URL } });
      }
      log("dashboard…");
      launch("dashboard", path.join(FRONTEND_DIR, "node_modules", ".bin", "next"), ["start", "-p", String(PORTS.dashboard)], FRONTEND_DIR, {
        QUEUELAB_API_URL: API_URL,
      });
    }
    await waitFor("dashboard", `${DASHBOARD_URL}/jobs`, 60_000);
    log("pila lista");
  } catch (error) {
    await stop();
    throw error;
  }

  return stop;
}
