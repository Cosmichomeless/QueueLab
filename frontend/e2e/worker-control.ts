import fs from "node:fs";
import { WORKER_PID_FILE } from "./stack";

/**
 * Congela y reanuda el proceso del worker (SIGSTOP/SIGCONT) para poder ver un trabajo esperando en la cola: con el
 * worker parado la API lo deja en `QUEUED` y RabbitMQ lo retiene hasta que alguien lo consuma. El proceso lo arranca
 * y lo apaga `global-setup.ts`, así que un test que falle no deja procesos huérfanos. Solo POSIX (macOS y Linux).
 */
function workerPid(): number {
  return Number(fs.readFileSync(WORKER_PID_FILE, "utf8"));
}

export function pauseWorker() {
  process.kill(workerPid(), "SIGSTOP");
}

export function resumeWorker() {
  process.kill(workerPid(), "SIGCONT");
}
