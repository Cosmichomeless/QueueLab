# Stack completo con Docker Compose (#55)

`docker compose up -d --wait` levanta **todo el flujo CSV**: PostgreSQL, RabbitMQ, Redis, la API, el worker y el
dashboard. El fichero es [`docker-compose.yml`](../docker-compose.yml) (proyecto `queuelab`).

```bash
docker compose up -d --wait        # la primera vez construye las imágenes (unos minutos)
open http://127.0.0.1:3000         # dashboard
docker compose down                # para y elimina contenedores y red; los datos se conservan
```

## Servicios

| Servicio | Imagen | Puerto en el host | Depende de (`service_healthy`) | Healthcheck |
| --- | --- | --- | --- | --- |
| `postgres` | `postgres:18-alpine` | `127.0.0.1:5434` | — | `pg_isready` |
| `rabbitmq` | `rabbitmq:4-management-alpine` | `127.0.0.1:5672` y `15672` (consola) | — | `rabbitmq-diagnostics ping` |
| `redis` | `redis:7-alpine` | `127.0.0.1:6379` | — | `redis-cli ping` |
| `api` | `queuelab-api` (`backend/api/Dockerfile`) | `127.0.0.1:8080` | postgres, rabbitmq, redis | `GET /actuator/health` (definido en la imagen) |
| `worker` | `queuelab-worker` (`backend/worker/Dockerfile`) | ninguno | postgres, rabbitmq, **api** | `GET /metrics` en `127.0.0.1:8081` dentro del contenedor |
| `dashboard` | `queuelab-dashboard` (`frontend/Dockerfile`) | `127.0.0.1:3000` | api | `GET /jobs` (definido en la imagen) |
| `prometheus`, `grafana` | ver [observabilidad](observability/dashboard.md) | `127.0.0.1:9090` y `3300` | — | perfil `observability` |

**Orden de arranque.** La API aplica las migraciones de Flyway al iniciar y el worker asume que el esquema existe, por
eso el worker espera a una API sana. El dashboard espera a la API para no mostrar errores en el primer arranque.

**Todos los puertos se publican solo en `127.0.0.1`.** El `/metrics` del worker no se publica: solo lo alcanza
Prometheus por la red de Compose (`QUEUELAB_METRICS_ADDRESS=0.0.0.0` dentro del contenedor).

## Red y volúmenes

- **Red.** Una sola, con nombre fijo `queuelab` (`networks.default.name`). Los servicios se resuelven por nombre
  (`postgres`, `rabbitmq`, `redis`, `api`, `worker`). No se declara `networks:` por servicio a propósito: si solo
  algunos lo declaran, el resto queda en otra red y no se ven.
- **Volúmenes** (se conservan con `docker compose down`; `down -v` los **borra**):

| Volumen | Contenido |
| --- | --- |
| `postgres-data` | Base de datos |
| `rabbitmq-data` | Colas y mensajes persistentes |
| `storage-data` | Entradas y resultados de los trabajos, montado en `/data/storage` en **API y worker** (la API sirve lo que escribe el worker) |
| `prometheus-data`, `grafana-data` | Solo con el perfil `observability` |

Redis no persiste a disco: solo guarda los contadores del límite de envíos, con expiración.

## Configuración

Los valores por defecto son **solo para desarrollo local**. Se sobrescriben con un `.env` (ver
[`.env.example`](../.env.example)): `POSTGRES_*`, `RABBITMQ_*`, `REDIS_PORT`, `API_PORT` (8080) y `DASHBOARD_PORT`
(3000).

- `QUEUELAB_API_URL` del dashboard es la URL de la API **tal como la ve el navegador** (`http://localhost:8080`), no un
  nombre interno de Compose: el navegador llama a la API directamente. Se lee en tiempo de ejecución, no en el build.
- `QUEUELAB_CORS_ALLOWED_ORIGINS` de la API incluye `http://localhost:<DASHBOARD_PORT>` y
  `http://127.0.0.1:<DASHBOARD_PORT>`.

## Solo la infraestructura (API, worker o dashboard en el host)

Para desarrollar con la aplicación fuera de contenedores, levante únicamente lo que necesita:

```bash
docker compose up -d --wait postgres rabbitmq redis
```

Un `docker compose up` sin nombres arranca **todo**, también la API en el 8080 y el dashboard en el 3000: si ya tiene
algo en esos puertos, ponga otros (`API_PORT`, `DASHBOARD_PORT`) o levante solo la infraestructura.

> **`localhost` y `127.0.0.1` pueden ser servidores distintos.** Compose publica en IPv4 (`127.0.0.1`). Si otro
> proceso del host escucha el mismo puerto en IPv6 (`*:3000`), el navegador puede conectar con él al abrir
> `localhost`. Pasó en la verificación: `localhost:3000` abría otra web de desarrollo. Use `http://127.0.0.1:3000`.

## Réplicas del worker

`docker compose up -d --wait --scale worker=2`. El worker no tiene `container_name` ni puertos publicados, así que no
hay conflictos; RabbitMQ reparte los mensajes entre las réplicas.

## Observabilidad con el stack en contenedores

El Prometheus por defecto apunta a la API y al worker del **host**. Con el stack en contenedores use la configuración
alternativa [`prometheus.compose.yml`](../observability/prometheus/prometheus.compose.yml), que apunta a `api:8080` y
descubre las réplicas del worker por DNS (`dns_sd_configs` sobre `worker`, puerto 8081):

```bash
PROMETHEUS_CONFIG=./observability/prometheus/prometheus.compose.yml \
  docker compose --profile observability up -d --wait
```

Grafana queda en `http://127.0.0.1:3300`. Más detalle en [`observability/dashboard.md`](observability/dashboard.md).

## Verificado y no verificado

Verificado en local (macOS, Docker Desktop):

- `docker compose config -q` correcto y `docker compose up -d --wait --build` desde cero: los **6 servicios** pasan
  a `healthy`.
- Flujo CSV por la API: `POST /api/v1/jobs/csv` → 201 → `COMPLETED` (20 000 filas, 1 intento) → descarga del
  resultado (200), escrito por el worker y servido por la API a través del volumen compartido.
- Flujo desde el **navegador** (Chromium con Playwright) contra `http://127.0.0.1:3000`: `__QUEUELAB_API_URL__`
  inyectada, subida del CSV, `COMPLETED`, descarga del resultado, llamadas del navegador a la API en el 8080 y **sin
  errores de consola ni de CORS**.
- Con el perfil `observability` y `prometheus.compose.yml`: los objetivos `queuelab-api` y `queuelab-worker`
  figuran en `up`.
- `--scale worker=2`: dos réplicas sanas, ambas descubiertas por Prometheus; 8 trabajos `COMPLETED` repartidos entre
  las dos (6 y 2).
- Persistencia: `docker compose down` (sin `-v`) y `up` de nuevo conservan los trabajos y los resultados.
- `docker compose up -d --wait postgres rabbitmq redis` levanta solo esos tres servicios.
- El worker corre con uid 10001 y su `8081` no es accesible desde el host.

**No verificado:**

- Linux, otras versiones de Docker/Compose, o un orquestador distinto (Swarm, Kubernetes).
- Más de dos réplicas del worker, y el comportamiento bajo carga sostenida.
- Un arranque en una máquina sin red: la primera construcción descarga imágenes base, dependencias de Maven y npm, y
  las fuentes de Google que usa el build del dashboard.
- El resto de navegadores (solo Chromium).

## Límites

- La primera construcción tarda varios minutos y necesita red; las siguientes aprovechan la caché de capas.
- Las credenciales por defecto son de desarrollo. No exponga estos puertos fuera de `127.0.0.1` ni use el fichero tal
  cual en producción.
- Un `docker compose up` a secas ya no levanta solo la infraestructura: ver la sección correspondiente más arriba.
