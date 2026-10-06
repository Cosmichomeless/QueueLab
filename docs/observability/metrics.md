# Métricas de trabajos y cola

QueueLab expone métricas en formato Prometheus desde sus dos procesos. Todas llevan la etiqueta
`application` (`queuelab-api` o `queuelab-worker`) para distinguir el origen.

## Dónde consultarlas

| Proceso | Endpoint | Puerto por defecto | Notas |
|---|---|---|---|
| API | `GET /actuator/prometheus` | el del servidor HTTP de la API | Actuator; `management.endpoints.web.exposure.include=health,info,prometheus` |
| Worker | `GET /metrics` | `8081`, solo en `127.0.0.1` | Servidor HTTP del JDK (el worker no tiene servidor web); cualquier otro método devuelve `405` |

Configuración del worker (`backend/worker/src/main/resources/application.yml`):

| Variable | Propiedad | Defecto |
|---|---|---|
| `QUEUELAB_METRICS_ENABLED` | `queuelab.metrics.enabled` | `true` |
| `QUEUELAB_METRICS_ADDRESS` | `queuelab.metrics.address` | `127.0.0.1` |
| `QUEUELAB_METRICS_PORT` | `queuelab.metrics.port` | `8081` |

Configuración de la API: `QUEUELAB_METRICS_REFRESH_INTERVAL` (`queuelab.metrics.refresh.interval`, defecto `15s`).

## Catálogo

### Worker

| Métrica | Tipo | Etiquetas | Qué mide |
|---|---|---|---|
| `queuelab_job_execution_seconds` | histograma | `type`, `outcome` | Duración de un intento, desde el reclamo hasta guardar su resultado. |
| `queuelab_job_wait_seconds` | histograma | `type` | Espera en cola: de la creación del trabajo a su **primer** intento (los reintentos esperan a propósito y no cuentan). |
| `queuelab_job_retries_total` | contador | `type`, `cause` | Reintentos programados (ya guardados). |
| `queuelab_job_dead_letters_total` | contador | `type`, `cause` | Trabajos que agotaron intentos y pasaron a la DLQ (ya guardados). |
| `queuelab_messages_rejected_total` | contador | `reason` | Mensajes que el consumidor rechazó sin reencolar. |

Cubos de los histogramas: 10 ms, 50 ms, 250 ms, 1 s, 5 s, 15 s, 60 s, 5 min, 30 min.

### API

Son gauges que leen una foto en memoria; la foto se refresca cada 15 s (ver limitaciones).

| Métrica | Etiquetas | Qué mide |
|---|---|---|
| `queuelab_jobs` | `status` | Trabajos por estado (siempre los cinco, también con valor 0). |
| `queuelab_outbox_pending` | — | Eventos del outbox listos para publicar y aún sin confirmar. |
| `queuelab_outbox_oldest_pending_age_seconds` | — | Antigüedad del evento pendiente más antiguo (0 si no hay ninguno). |
| `queuelab_queue_messages` | `queue` | Mensajes listos en la cola de RabbitMQ. |
| `queuelab_queue_consumers` | `queue` | Consumidores conectados a la cola. |

Además, ambos procesos exponen las métricas estándar de Micrometer (JVM, HTTP del servidor en la API, etc.).

## Cardinalidad controlada

Cada etiqueta toma valores de un conjunto cerrado:

| Etiqueta | Valores posibles |
|---|---|
| `status` | `queued`, `running`, `completed`, `failed`, `retrying` |
| `outcome` | `completed`, `failed`, `retry`, `dead_letter` |
| `cause` | `transient`, `lease_expired` |
| `reason` | `malformed`, `unknown_job` |
| `queue` | `queuelab.jobs.queued`, `queuelab.jobs.queued.dlq` |
| `type` | tipo del trabajo si cumple `[a-z0-9][a-z0-9._-]{0,31}`; cualquier otro valor se agrupa como `other` |
| `application` | `queuelab-api`, `queuelab-worker` |

**Nunca** se usan `jobId` ni `correlationId` como etiqueta: cada valor crearía una serie nueva. Para seguir un
trabajo concreto están los logs (#45) y, después, las trazas. Los tests comprueban que el id del trabajo no aparece en
ninguna salida de `/metrics` ni de `/actuator/prometheus`.

## Cómo leer el estado de la cola

- **¿Se acumula trabajo?** `queuelab_queue_messages{queue="queuelab.jobs.queued"}` y `queuelab_outbox_pending` subiendo.
- **¿Se publica?** `queuelab_outbox_oldest_pending_age_seconds` creciendo indica que el dispatcher o RabbitMQ no avanzan.
- **¿Hay consumidores?** `queuelab_queue_consumers` a 0 con mensajes en cola: el worker está caído o desconectado.
- **¿Fallan los trabajos?** `rate(queuelab_job_execution_seconds_count{outcome=~"failed|dead_letter"}[5m])`.
- **¿Cuánto esperan?** `histogram_quantile(0.95, sum by (le) (rate(queuelab_job_wait_seconds_bucket[5m])))`.
- **DLQ y reintentos:** `queuelab_queue_messages{queue="queuelab.jobs.queued.dlq"}`, `queuelab_job_retries_total`,
  `queuelab_job_dead_letters_total`.

## Seguridad

Los endpoints de métricas no llevan autenticación. El worker escucha solo en `127.0.0.1`; para que un Prometheus en
otro contenedor lo alcance hay que abrirlo expresamente (`QUEUELAB_METRICS_ADDRESS=0.0.0.0`) y dejarlo en una red
interna, sin publicarlo en el host. En la API, `/actuator/prometheus` debe quedar fuera de la red pública (proxy o red
interna).

## Limitaciones

- **Foto cada 15 s:** los gauges de la API (`queuelab_jobs`, outbox y colas) reflejan el estado de la última lectura,
  no el instante del scrape. A cambio, un scrape nunca lanza consultas ni habla con el broker.
- **Fuente caída = `NaN`:** si PostgreSQL o RabbitMQ no responden, las series de esa fuente pasan a `NaN` (sin dato) en
  lugar de conservar un valor viejo; las demás fuentes siguen respondiendo. El log avisa solo al cambiar de estado.
- **Worker con el puerto ocupado:** el worker sigue procesando trabajos sin métricas y deja un error en el log.
- **Contadores solo tras guardar:** `retries` y `dead_letters` se incrementan después de persistir el resultado; si el
  guardado falla, no se cuenta (el trabajo se recuperará por lease y se contará entonces).
- **Contadores en memoria:** se reinician con el proceso; Prometheus lo gestiona con `rate()`/`increase()`.
- **Varias réplicas del worker:** cada una expone su propio `/metrics`; hay que agregarlas en Prometheus. Los gauges de
  la API describen el estado global, así que con varias réplicas de la API no deben sumarse (usar `max`).

## Verificación

- `backend/worker/src/test/java/com/queuelab/worker/MetricsTest.java`: resultados, reintentos, DLQ, rechazos,
  `/metrics` real por HTTP, `405` en POST y agrupación de tipos (PostgreSQL y RabbitMQ con Testcontainers).
- `backend/api/src/test/java/com/queuelab/api/QueueMetricsTest.java`: trabajos por estado, outbox y cola tras
  `dispatchPending`, `NaN` con fuente caída, etiquetas cerradas y ausencia de ids.
