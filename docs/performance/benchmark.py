#!/usr/bin/env python3
"""Benchmark de throughput y latencia de QueueLab según el límite de concurrencia del worker.

Reproducible: genera los CSV de forma determinista, lanza la API y el worker reales (los jar empaquetados) contra
el PostgreSQL/RabbitMQ/Redis de `docker-compose.yml` y, para cada valor de `queuelab.worker.concurrency`,
mide cuánto tarda el worker en vaciar una cola ya llena.

Método (por cada concurrencia):
  1. Con el worker parado, la API acepta N trabajos (`POST /api/v1/jobs/csv` o `/jobs`); el outbox los publica
     en RabbitMQ y se espera a que la cola esté llena (`publishPerSecond` es el ritmo del outbox).
  2. Se arranca el worker con la concurrencia a probar y se espera a que todos terminen.
  3. Las métricas salen de los timestamps de PostgreSQL (`started_at`, `finished_at`): una sola fuente de reloj.

Requisitos: `./mvnw -q package -DskipTests` en backend/, `docker compose up -d postgres rabbitmq redis`,
Java 25 en el PATH (o JAVA_HOME) y python3. Solo usa la biblioteca estándar.

Uso:
  python3 docs/performance/benchmark.py [--scenario csv|noop|submit] [--jobs 100] [--rows 100000]
                                        [--concurrency 1,2,4,8,16] [--repeat 3] [--output results.json]
                                        [--api-env CLAVE=VALOR ...] [--worker-env CLAVE=VALOR ...]
"""
import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API_JAR = ROOT / "backend/api/target/queuelab-api-0.1.0-SNAPSHOT.jar"
WORKER_JAR = ROOT / "backend/worker/target/queuelab-worker-0.1.0-SNAPSHOT.jar"
API_PORT = 18080
WORKER_PORT = 18081
DB = "queuelab_bench43"


def sh(*cmd, check=True, input=None):
    result = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, input=input)
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} -> {result.returncode}\n{result.stdout}\n{result.stderr}")
    return result.stdout.strip()


def psql(sql, db=DB):
    return sh("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "queuelab", "-d", db, "-At", "-c", sql)


def java_bin():
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin/java") if home else "java"


def generate_csv(path, rows):
    """CSV determinista de 6 columnas (3 numéricas, 3 de texto); mismo contenido en cualquier máquina."""
    with open(path, "w", encoding="utf-8", newline="") as out:
        out.write("id,amount,quantity,city,product,note\n")
        cities = ["Madrid", "Lima", "Quito", "Bogotá", "Santiago", "Montevideo"]
        for i in range(rows):
            out.write(f"{i},{(i * 7919) % 100000 / 100:.2f},{i % 97},{cities[i % 6]},"
                      f"producto-{i % 1000},\"nota {i}, con coma\"\n")


def post_multipart(url, file_path):
    boundary = uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"bench.csv\"\r\n"
            f"Content-Type: text/csv\r\n\r\n").encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    body = head + Path(file_path).read_bytes() + tail
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    return urllib.request.urlopen(request, timeout=60).status


def post_json(url, payload):
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(request, timeout=60).status


class Process:
    def __init__(self, name, jar, env, log_dir):
        self.log = open(Path(log_dir) / f"{name}.log", "w")
        self.proc = subprocess.Popen([java_bin(), "-jar", str(jar)], cwd=ROOT, stdout=self.log,
                                     stderr=subprocess.STDOUT, env={**os.environ, **env})

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.close()


def wait_http(url, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if urllib.request.urlopen(url, timeout=2).status == 200:
                return
        except (urllib.error.URLError, ConnectionError, OSError):
            time.sleep(0.5)
    raise RuntimeError(f"{url} no respondió en {timeout} s")


def percentile(values, p):
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round(p / 100 * len(ordered) + 0.5) - 1))
    return ordered[index]


def run_once(args, concurrency, payload_file, storage, logs, api_env, worker_base_env):
    psql("delete from jobs")
    sh("docker", "compose", "exec", "-T", "rabbitmq", "rabbitmqctl", "purge_queue", "queuelab.jobs.queued",
       check=False)
    # --- 1. La API llena la cola con el worker parado ---
    submit_started = time.time()
    latencies = []

    def submit(_):
        t0 = time.perf_counter()
        if args.scenario == "noop":
            post_json(f"http://localhost:{API_PORT}/api/v1/jobs", {"type": "noop"})
        else:
            post_multipart(f"http://localhost:{API_PORT}/api/v1/jobs/csv", payload_file)
        latencies.append((time.perf_counter() - t0) * 1000)

    with ThreadPoolExecutor(args.submit_clients) as pool:
        list(pool.map(submit, range(args.jobs)))
    submit_seconds = time.time() - submit_started
    # El outbox publica en lotes (queuelab.outbox.batch-size cada dispatch.interval): se espera a que la cola esté
    # llena para que la medición del worker no quede limitada por la publicación.
    while int(psql("select count(*) from outbox_events where published_at is null")) > 0:
        time.sleep(0.2)
    publish_seconds = time.time() - submit_started
    result = {
        "concurrency": concurrency,
        "submitSeconds": round(submit_seconds, 2),
        "submitPerSecond": round(args.jobs / submit_seconds, 1),
        "publishSeconds": round(publish_seconds, 2),
        "publishPerSecond": round(args.jobs / publish_seconds, 1),
        "submitP50Ms": round(percentile(latencies, 50), 1),
        "submitP95Ms": round(percentile(latencies, 95), 1),
    }
    if args.scenario == "submit":
        return result
    # --- 2. Se arranca el worker y se espera a que vacíe la cola ---
    worker = Process(f"worker-c{concurrency}", WORKER_JAR, {
        **worker_base_env, "QUEUELAB_WORKER_CONCURRENCY": str(concurrency)}, logs)
    try:
        deadline = time.time() + args.timeout
        while time.time() < deadline:
            done = int(psql("select count(*) from jobs where status in ('COMPLETED','FAILED')"))
            if done >= args.jobs:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError(f"timeout: {done}/{args.jobs} terminados con concurrencia {concurrency}")
    finally:
        worker.stop()
    # --- 3. Métricas desde PostgreSQL ---
    failed = int(psql("select count(*) from jobs where status = 'FAILED'"))
    makespan = float(psql("select extract(epoch from (max(finished_at) - min(started_at))) from jobs"))
    service = [float(v) for v in psql(
        "select extract(epoch from (finished_at - started_at)) * 1000 from jobs").split()]
    result.update({
        "failed": failed,
        "drainSeconds": round(makespan, 2),
        "throughputPerSecond": round(args.jobs / makespan, 2),
        "serviceP50Ms": round(percentile(service, 50), 1),
        "serviceP95Ms": round(percentile(service, 95), 1),
        "serviceMaxMs": round(max(service), 1),
    })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenario", choices=["csv", "noop", "submit"], default="csv")
    parser.add_argument("--jobs", type=int, default=100)
    parser.add_argument("--rows", type=int, default=100_000, help="filas del CSV (escenario csv)")
    parser.add_argument("--concurrency", default="1,2,4,8,16")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--submit-clients", type=int, default=8)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--api-env", action="append", default=[], metavar="CLAVE=VALOR",
                        help="variable de entorno extra para la API (p. ej. QUEUELAB_OUTBOX_BATCH_SIZE=200)")
    parser.add_argument("--worker-env", action="append", default=[], metavar="CLAVE=VALOR",
                        help="variable de entorno extra para el worker (p. ej. QUEUELAB_WORKER_PREFETCH=5)")
    parser.add_argument("--output")
    args = parser.parse_args()
    levels = [int(v) for v in args.concurrency.split(",")]

    for jar in (API_JAR, WORKER_JAR):
        if not jar.exists():
            sys.exit(f"Falta {jar}: ejecuta `./mvnw -q package -DskipTests` en backend/")
    work = Path(tempfile.mkdtemp(prefix="queuelab-bench-"))
    storage, logs = work / "storage", work / "logs"
    storage.mkdir()
    logs.mkdir()
    payload = work / "bench.csv"
    generate_csv(payload, args.rows)
    print(f"Escenario {args.scenario}: {args.jobs} trabajos, CSV de {args.rows} filas "
          f"({payload.stat().st_size / 1048576:.1f} MiB), concurrencias {levels}, {args.repeat} repeticiones",
          file=sys.stderr)

    sh("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "queuelab", "-d", "queuelab", "-c",
       f"drop database if exists {DB}")
    sh("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "queuelab", "-d", "queuelab", "-c",
       f"create database {DB}")
    common = {
        "QUEUELAB_DB_URL": f"jdbc:postgresql://localhost:5434/{DB}",
        "QUEUELAB_STORAGE_DIRECTORY": str(storage),
    }
    api_env = {**common, "SPRING_PROFILES_ACTIVE": "local", "SERVER_PORT": str(API_PORT),
               # El benchmark mide el worker: se desactivan el límite de envíos y la contrapresión.
               "QUEUELAB_RATE_LIMIT_ENABLED": "false",
               "QUEUELAB_BACKPRESSURE_MAX_PENDING": str(args.jobs * 10)}
    worker_env = {**common, "SPRING_PROFILES_ACTIVE": "local", "SERVER_PORT": str(WORKER_PORT)}
    api_env.update(item.split("=", 1) for item in args.api_env)
    worker_env.update(item.split("=", 1) for item in args.worker_env)
    api = Process("api", API_JAR, api_env, logs)
    runs = []
    try:
        wait_http(f"http://localhost:{API_PORT}/actuator/health")
        for repetition in range(args.repeat + 1):  # la primera vuelta calienta la JVM y no se publica
            for level in levels:
                outcome = run_once(args, level, payload, storage, logs, api_env, worker_env)
                outcome["repetition"] = repetition
                outcome["warmup"] = repetition == 0
                runs.append(outcome)
                print(json.dumps(outcome), file=sys.stderr)
    finally:
        api.stop()
        sh("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "queuelab", "-d", "queuelab", "-c",
           f"drop database if exists {DB} with (force)", check=False)
        sh("docker", "compose", "exec", "-T", "rabbitmq", "rabbitmqctl", "purge_queue", "queuelab.jobs.queued",
           check=False)
        shutil.rmtree(work, ignore_errors=True)

    summary = []
    for level in levels:
        measured = [r for r in runs if r["concurrency"] == level and not r["warmup"]]
        row = {"concurrency": level, "runs": len(measured)}
        for key in measured[0]:
            if key in ("concurrency", "repetition", "warmup"):
                continue
            row[key] = round(statistics.median(r[key] for r in measured), 2)
        summary.append(row)
    report = {"scenario": args.scenario, "jobs": args.jobs, "rows": args.rows, "repeat": args.repeat,
              "summary": summary, "runs": runs}
    if args.output:
        Path(args.output).write_text(json.dumps(report, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
