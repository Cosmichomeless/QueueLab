# Desarrollo local

Referencia de puesta en marcha con la API, el worker y el dashboard **fuera** de contenedores. Para probar el flujo
completo solo con Docker basta `docker compose up -d --wait` (ver [`compose-stack.md`](compose-stack.md)).

## Requisitos

- Docker con Compose v2
- JDK 25 (el Maven Wrapper se incluye: no hace falta instalar Maven)
- Node.js 22 y npm (la versión del `Dockerfile` del dashboard y de su CI; no se ha probado con otra)

## 1. Configuración

```bash
cp .env.example .env                  # valores de desarrollo; .env no se versiona
cp frontend/.env.example frontend/.env.local
```

## 2. Servicios locales (PostgreSQL, RabbitMQ y Redis)

```bash
docker compose up -d --wait postgres rabbitmq redis   # solo la infraestructura; espera a que estén healthy
```

> Con `docker compose up -d --wait` a secas se levanta **todo el stack** (también API, worker y dashboard en
> contenedores): ver [`compose-stack.md`](compose-stack.md). No lo mezcle con los pasos 3 y 4: la API y el dashboard
> del contenedor ocupan los puertos 8080 y 3000.

PostgreSQL queda en `localhost:5434`, RabbitMQ en `localhost:5672` (consola en http://localhost:15672) y Redis en
`localhost:6379`. Credenciales de desarrollo: `queuelab` / `queuelab`.

## 3. Backend

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS; en Linux, la ruta de su JDK 25
cd backend && ./mvnw verify                        # compila y prueba (las pruebas usan Docker)

# En terminales separadas, desde la raíz del repositorio:
SPRING_PROFILES_ACTIVE=local java -jar backend/api/target/queuelab-api-0.1.0-SNAPSHOT.jar
SPRING_PROFILES_ACTIVE=local java -jar backend/worker/target/queuelab-worker-0.1.0-SNAPSHOT.jar
```

La API escucha en http://localhost:8080 (`/actuator/health`) y aplica las migraciones al arrancar. El perfil `local`
aporta las contraseñas de desarrollo; sin él hay que definir `QUEUELAB_DB_PASSWORD` y `QUEUELAB_RABBITMQ_PASSWORD`.

## 4. Dashboard

```bash
cd frontend
npm ci
npm run dev                           # http://localhost:3000
```

Otros scripts: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`. El dashboard llama a la API desde el
navegador (`QUEUELAB_API_URL`, por defecto `http://localhost:8080`), así que la API debe admitir su origen: por
defecto `http://localhost:3000` (`QUEUELAB_CORS_ALLOWED_ORIGINS`). Detalles en [`frontend/README.md`](../frontend/README.md).

## Parar y limpiar

```bash
docker compose down                   # conserva los datos
docker compose down -v                # borra también los volúmenes
```

## Capturas del README

Se regeneran con `./scripts/screenshots.sh`; el comando y sus requisitos están en el [README](../README.md#capturas).

## Índice de documentación

- [`backend/README.md`](../backend/README.md): variables `QUEUELAB_*`, perfiles, migraciones, mensajería, outbox y worker.
- [`.env.example`](../.env.example) y [`frontend/.env.example`](../frontend/.env.example): todas las variables explicadas.
- [`performance/benchmark-results.md`](performance/benchmark-results.md): benchmark de throughput y latencia según el límite de concurrencia del worker (reproducible con `docs/performance/benchmark.py`).
- [`performance/capacity.md`](performance/capacity.md): capacidad, trade-offs de cada límite (concurrencia, `prefetch`, contrapresión, cuota, outbox, lease) y cómo cambiarlos con seguridad.
- [`observability/tracing.md`](observability/tracing.md): trazas OpenTelemetry que unen petición HTTP, outbox y worker (spans, configuración, degradación).
- [`observability/metrics.md`](observability/metrics.md): métricas Prometheus de trabajos y cola (catálogo, endpoints y limitaciones).
- [`observability/dashboard.md`](observability/dashboard.md): Prometheus y Grafana locales con el dashboard de cola, reintentos y DLQ (`docker compose --profile observability up -d`).
- [`security/upload-api-review.md`](security/upload-api-review.md): revisión de seguridad de la subida de ficheros y la API.
- [`csv-workload.md`](csv-workload.md): contrato del trabajo `csv-import` (formato, límites, estadísticas y fallos).
- [`integration-tests.md`](integration-tests.md): tests de integración con PostgreSQL y RabbitMQ reales (Testcontainers).
- [`e2e-dashboard.md`](e2e-dashboard.md): pruebas e2e del dashboard con Playwright.
- [`containers-backend.md`](containers-backend.md) y [`containers-frontend.md`](containers-frontend.md): imágenes de API, worker y dashboard.
- [`ci-backend.md`](ci-backend.md): CI del backend y el worker en GitHub Actions.
- [`ci-frontend.md`](ci-frontend.md): CI del dashboard (lint, tipos, tests y build) en GitHub Actions.
- [`compose-stack.md`](compose-stack.md): stack completo (API, worker, dashboard, PostgreSQL, RabbitMQ y Redis) con `docker compose up`.
- [`deployment/services-and-costs.md`](deployment/services-and-costs.md): dónde correría fuera del portátil con 0 €, quién opera cada pieza, costes y límites.
- [`deployment/secrets-tls-access.md`](deployment/secrets-tls-access.md): secretos fuera de Git, HTTPS con Caddy y política de acceso a la API (`docker-compose.prod.yml`).
- [`deployment/data-services-and-backups.md`](deployment/data-services-and-backups.md): migraciones de Flyway, copia y restauración (`scripts/backup.sh`, `scripts/restore.sh`), retención y durabilidad de colas y DLQ.
- [`notas-originales.md`](notas-originales.md): notas de partida del proyecto.
