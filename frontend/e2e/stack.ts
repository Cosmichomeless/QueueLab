import path from "node:path";

/**
 * Puertos y entorno de la pila que levantan las pruebas e2e. Todo en 127.0.0.1 y en puertos distintos de los de
 * desarrollo (compose, API 8080, dashboard 3000) para poder ejecutarlas con el entorno de desarrollo encendido.
 * Los puertos de la infraestructura deben coincidir con `compose.yml`.
 */
export const PORTS = { postgres: 15441, rabbitmq: 15673, api: 18091, workerMetrics: 18092, dashboard: 13001 } as const;

export const FRONTEND_DIR = path.resolve(__dirname, "..");
export const BACKEND_DIR = path.resolve(FRONTEND_DIR, "..", "backend");
export const OUT_DIR = path.join(FRONTEND_DIR, "e2e", ".out");
export const WORKER_PID_FILE = path.join(OUT_DIR, "worker.pid");

export const API_URL = `http://localhost:${PORTS.api}`;

/** Dashboard bajo prueba: el `next start` que arranca la configuración, o uno ya en marcha (p. ej. el contenedor). */
export const DASHBOARD_URL = process.env.E2E_DASHBOARD_URL || `http://localhost:${PORTS.dashboard}`;
export const DASHBOARD_IS_EXTERNAL = Boolean(process.env.E2E_DASHBOARD_URL);

/** Variables que comparten la API y el worker (base de datos, broker y almacenamiento de ficheros). */
function backendEnv(): Record<string, string> {
  return {
    QUEUELAB_DB_URL: `jdbc:postgresql://localhost:${PORTS.postgres}/queuelab`,
    QUEUELAB_DB_USER: "queuelab",
    QUEUELAB_DB_PASSWORD: "queuelab-e2e",
    QUEUELAB_RABBITMQ_HOST: "localhost",
    QUEUELAB_RABBITMQ_PORT: String(PORTS.rabbitmq),
    QUEUELAB_RABBITMQ_USER: "queuelab",
    QUEUELAB_RABBITMQ_PASSWORD: "queuelab-e2e",
    QUEUELAB_STORAGE_DIRECTORY: path.join(OUT_DIR, "storage"),
    // Sin Redis: el límite de envíos (que deja pasar si no lo alcanza) solo metería ruido.
    QUEUELAB_RATE_LIMIT_ENABLED: "false",
  };
}

export function apiEnv(): Record<string, string> {
  const origins = new Set([`http://localhost:${PORTS.dashboard}`, new URL(DASHBOARD_URL).origin]);
  return {
    ...backendEnv(),
    SERVER_PORT: String(PORTS.api),
    QUEUELAB_CORS_ALLOWED_ORIGINS: [...origins].join(","),
  };
}

export function workerEnv(): Record<string, string> {
  return { ...backendEnv(), QUEUELAB_METRICS_PORT: String(PORTS.workerMetrics) };
}
