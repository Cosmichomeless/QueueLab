# Dashboard local de métricas (Prometheus + Grafana)

Un Prometheus y un Grafana locales, con el dashboard **QueueLab** ya provisionado, para ver el estado de la cola, los
reintentos y la DLQ con datos reales. Usa las métricas del [catálogo](metrics.md) (#46). Es solo para desarrollo:
no lleva autenticación real ni persistencia pensada para producción.

## Arranque

```bash
docker compose up -d --wait postgres rabbitmq redis prometheus grafana   # infraestructura + prometheus y grafana
cd backend && ./mvnw -q -DskipTests package && cd ..

# API (puerto 8080) y worker (el worker debe abrir /metrics fuera de 127.0.0.1 para que lo alcance el contenedor)
SPRING_PROFILES_ACTIVE=local java -jar backend/api/target/queuelab-api-0.1.0-SNAPSHOT.jar
SPRING_PROFILES_ACTIVE=local QUEUELAB_METRICS_ADDRESS=0.0.0.0 java -jar backend/worker/target/queuelab-worker-0.1.0-SNAPSHOT.jar
```

| Servicio | URL | Notas |
|---|---|---|
| Grafana | <http://localhost:3300> | Abre directamente el dashboard QueueLab (acceso anónimo de solo lectura). Administración: `admin` / `admin` (`GRAFANA_USER`, `GRAFANA_PASSWORD`). |
| Prometheus | <http://localhost:9090> | `Status → Targets` debe mostrar `queuelab-api` y `queuelab-worker` en `UP`. |

Los puertos (`GRAFANA_PORT`, `PROMETHEUS_PORT`) y los servicios solo se publican en `127.0.0.1`. Grafana usa el 3300
para no chocar con el dashboard web (3000) ni con servidores de desarrollo habituales (3001).

Sin el perfil `observability` (y sin nombrarlos), Prometheus y Grafana no se levantan.

### Con el stack completo en contenedores

Si la API y el worker corren en Compose ([`docs/compose-stack.md`](../compose-stack.md)), Prometheus los alcanza por
el nombre del servicio (`api:8080`, `worker:8081`) con otra configuración de scrape:

```bash
PROMETHEUS_CONFIG=./observability/prometheus/prometheus.compose.yml docker compose --profile observability up -d --wait
```

El worker ya arranca con `QUEUELAB_METRICS_ADDRESS=0.0.0.0` dentro de la red de Compose; su puerto 8081 **no** se
publica en el host.

## Qué muestra

| Fila | Paneles |
|---|---|
| Estado de un vistazo | Cola principal, consumidores, **DLQ**, outbox pendiente, evento más antiguo del outbox, trabajos fallidos. Con colores de umbral (rojo = atención). |
| Cola y trabajos | Mensajes en la cola principal y en la DLQ; trabajos por estado. |
| **Reintentos y DLQ** | Reintentos, trabajos enviados a la DLQ y mensajes rechazados en la última hora; ritmo de reintentos por causa (`transient`, `lease_expired`); ritmo de dead-letters junto a los mensajes acumulados en la DLQ. |
| Rendimiento del worker | Intentos por resultado (`completed`, `failed`, `retry`, `dead_letter`), duración p50/p95/p99, espera en cola p50/p95, outbox. |
| Procesos | API y worker arriba (`up`), peticiones HTTP de la API por código. |

El dashboard está en [`observability/grafana/dashboards/queuelab.json`](../../observability/grafana/dashboards/queuelab.json)
y es de solo lectura en Grafana (`allowUiUpdates: false`): para cambiarlo, edita el JSON y reinicia Grafana
(`docker compose --profile observability restart grafana`).

## Configuración

- Scrape: [`observability/prometheus/prometheus.yml`](../../observability/prometheus/prometheus.yml), cada 15 s (igual que
  el refresco de los gauges de la API). Los destinos son `host.docker.internal:8080` (`/actuator/prometheus`) y
  `host.docker.internal:8081` (`/metrics`). En Linux, Compose añade `host.docker.internal` con `host-gateway`.
- Si la API usa otro puerto, cambia el destino en `prometheus.yml` y reinicia Prometheus.
- Datasource y dashboard se provisionan desde [`observability/grafana/provisioning/`](../../observability/grafana/provisioning/);
  no hay que configurar nada a mano.
- Retención de Prometheus: 7 días (volumen `prometheus-data`); Grafana guarda su estado en `grafana-data`.

## Verificación (real)

Con la API y el worker reales contra PostgreSQL/RabbitMQ/Redis de Compose:

1. Prometheus: los dos *targets* en `UP`.
2. Se enviaron CSV por `POST /api/v1/jobs/csv`; para provocar fallos transitorios se pausó el worker, se borró el fichero
   de entrada de varios trabajos y se reanudó (`QUEUELAB_WORKER_RETRY_MAX_ATTEMPTS=2`, `…_INITIAL_DELAY=2s`).
   Resultado en `/metrics`: `completed 11`, `retry 7`, `dead_letter 7`, `retries_total 7`, `dead_letters_total 7`.
3. Las 25 consultas de los paneles se ejecutaron contra Grafana (`/api/datasources/proxy/...`): todas devuelven
   series con datos (DLQ, reintentos por causa, percentiles de duración y espera, trabajos por estado…).
4. Captura del dashboard con Chromium: todos los paneles pintados con datos (los de DLQ en rojo).

## Limitaciones

- **API y worker corren en el host:** el Compose actual no los incluye (lo hará #55); entonces los destinos pasarán a
  ser `api:8080` y `worker:8081`, dentro de la red de Compose.
- **El worker debe abrir `/metrics`:** por defecto solo escucha en `127.0.0.1` (ver [Seguridad](metrics.md#seguridad));
  `QUEUELAB_METRICS_ADDRESS=0.0.0.0` lo expone a tu red local mientras dure la prueba.
- **Contadores y `increase()`:** si Prometheus ve un contador por primera vez ya con valor, no hay delta; los paneles
  de «última hora» arrancan en 0 hasta el siguiente cambio y `increase()` extrapola, por lo que puede dar decimales
  (se muestran redondeados). Un reinicio del worker reinicia sus contadores (`rate()` lo gestiona).
- **DLQ acumulada:** el panel de la DLQ cuenta los mensajes que hay en la cola de RabbitMQ, incluidos los anteriores
  al arranque del worker; el volumen `rabbitmq-data` los conserva entre ejecuciones.
- **Varios workers:** habría que añadir cada uno como destino; los paneles agregan con `sum`/`max`.
- **Sin alertas:** solo visualización; las reglas de alerta quedan fuera del alcance.
- **No verificado:** el arranque en Linux (`host-gateway`) no se ha probado.
