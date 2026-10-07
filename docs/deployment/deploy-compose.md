# Desplegar API, worker y dashboard (#61)

Cómo se levanta QueueLab completo con la configuración pública, qué comprobaciones de salud se pueden ejecutar y cuánta
memoria necesita cada servicio. Como en [`services-and-costs.md`](services-and-costs.md), **no hay un entorno
alojado**: «desplegar» aquí es `docker-compose.yml` + `docker-compose.prod.yml` **probado de verdad en local**, en un
proyecto aparte (`queuelab-prod-test`) con volúmenes propios, borrados al terminar. No se ha creado ninguna cuenta ni
se ha expuesto nada a Internet.

## Levantar el stack

```bash
cp .env.production.example .env.production      # y rellenar los secretos: ver secrets-tls-access.md
export COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml COMPOSE_ENV_FILES=.env.production
docker compose up -d --build --wait             # --wait espera a que todo esté «healthy»
docker compose ps
```

Con `COMPOSE_FILE` y `COMPOSE_ENV_FILES` exportadas, `scripts/backup.sh` y `scripts/restore.sh` usan la misma
configuración. Perfil opcional de métricas: `docker compose --profile observability up -d --wait` (necesita
`GRAFANA_PASSWORD`).

## Comprobaciones de salud

| Servicio | Comprobación (dentro del contenedor) | Dónde se define |
| --- | --- | --- |
| `api` | `GET /actuator/health` → `UP` (solo cuenta la base de datos: sin RabbitMQ ni Redis la API sigue aceptando trabajos) | Imagen de la API |
| `dashboard` | `GET /jobs` → 200 | Imagen del dashboard |
| `worker` | `GET :8081/metrics` → 200 | `docker-compose.yml` |
| `caddy` | `GET 127.0.0.1:2019/config/` (API de administración, solo dentro del contenedor) | `docker-compose.prod.yml` |
| `postgres`, `rabbitmq`, `redis` | `pg_isready`, `rabbitmq-diagnostics -q ping`, `redis-cli ping` | `docker-compose.yml` |

Verificables desde fuera (con las credenciales de `.env.production`; `-k` porque en local el certificado es de la CA
interna de Caddy):

```bash
docker compose ps --format '{{.Service}} {{.Status}}'          # los 7 servicios en «healthy»
curl -sk -o /dev/null -w '%{http_code}\n' https://localhost:8443/api/v1/jobs                       # 401 sin credenciales
curl -sk -u usuario:contraseña -o /dev/null -w '%{http_code}\n' https://localhost:8443/api/v1/jobs # 200
curl -sk -u usuario:contraseña -o /dev/null -w '%{http_code}\n' https://localhost:8443/actuator/health  # 404: no se expone
docker compose exec api wget -qO- http://127.0.0.1:8080/actuator/health                             # ..."status":"UP"
```

El `/actuator` **no sale por Caddy a propósito**: la salud de la API se comprueba por dentro del contenedor (o con
`docker compose ps`). Un monitor externo no puede consultarla con esta configuración.

## Límites de memoria

Cada servicio tiene `mem_limit` en `docker-compose.prod.yml`. Las JVM de API y worker calculan su heap como el 75 % del
**límite del contenedor** (`-XX:MaxRAMPercentage=75`, [`containers-backend.md`](../containers-backend.md)), así que sin
límite tomarían hasta el 75 % de la memoria de la máquina.

| Servicio | `mem_limit` | Pico sin límites (MiB) | Pico con límites (MiB) |
| --- | --- | --- | --- |
| `api` | 512 MiB | 260 | 235 |
| `worker` | 512 MiB | 271 | 157 |
| `rabbitmq` | 512 MiB | 181 | 164 |
| `postgres` | 512 MiB | 68 | 64 |
| `dashboard` | 256 MiB | 43 | 43 |
| `caddy` | 64 MiB | 15 | 23 |
| `redis` | 64 MiB | 8 | 10 |
| Suma de los límites | **2 432 MiB** (≈ 2,4 GiB) | | |

`prometheus` (25 MiB) y `grafana` (94 MiB), del perfil `observability`, **no tienen límite**: su consumo medido es
pequeño, pero el de Prometheus crece con la retención (7 días) y no se midió en un periodo largo.

**Cómo se midió.** Máquina de 7,65 GiB (Docker Desktop, macOS, arm64). Carga: 60 CSV de 30 000 filas
(1 351 425 bytes cada uno) subidos por HTTPS a través de Caddy, 10 en paralelo, y muestreo de `docker stats` cada
segundo hasta terminar. Los picos son el máximo muestreado, **no el máximo real**: un pico más corto que el intervalo
puede haberse escapado. Los límites son aproximadamente el doble del pico medido para absorber eso.

**RabbitMQ.** Calcula su umbral de memoria sobre la RAM de la **máquina** (con 7,65 GiB, `0,6 × 4,93 GB`), no sobre el
límite del contenedor: al llegar a los 512 MiB el kernel lo habría matado (OOM) antes de que su alarma frenase las
publicaciones. [`deploy/rabbitmq.conf`](../../deploy/rabbitmq.conf) fija `vm_memory_high_watermark.absolute = 384MiB`
(comprobado con `rabbitmqctl status`: 0,4027 GB). Si se llena, RabbitMQ bloquea publicadores y el outbox los retiene
hasta que se libere memoria. No se provocó esa alarma en las pruebas.

Si se cambia un límite, hay que repetir la carga: el heap de la JVM cambia con él.

## Verificado en esta issue

Con `docker compose up -d --wait --build` sobre `docker-compose.yml` + `docker-compose.prod.yml`:

- Los 7 servicios en `healthy` (Caddy incluido, con el healthcheck añadido aquí), con los límites aplicados
  (`docker inspect` → `HostConfig.Memory`).
- **Flujo de CSV de extremo a extremo por HTTPS:** subida con credenciales → 201 → `COMPLETED` → resultado descargable.
- **Carga con la configuración final:** 60 subidas de 1,35 MB, 10 en paralelo → 60 × 201 y 60 `COMPLETED` (183 en
  total en el proyecto de prueba); 0 reinicios, `OOMKilled=false` en los 9 contenedores, colas y DLQ vacías.
- **`scripts/backup.sh` y `scripts/restore.sh` con `docker-compose.prod.yml`:** copia con el stack en marcha (121
  `COMPLETED` y 1 `QUEUED` por tener el worker parado, Flyway 15, 243 ficheros); `docker compose down -v`; restauración.
  Resultado: 122 `COMPLETED`, el resultado antiguo se descarga con el **mismo SHA-256**, el trabajo `QUEUED` terminó
  solo con RabbitMQ vacío y sigue habiendo 401 sin credenciales. Tras `restore.sh` hay que ejecutar
  `docker compose up -d --wait`: el script solo arranca PostgreSQL, RabbitMQ, Redis, API y worker, no Caddy ni el
  dashboard.
- **Reinicio completo** (`docker compose restart`): todo vuelve a `healthy`, los trabajos siguen y un CSV nuevo termina.
- **Perfil `observability` con la configuración pública:** ver abajo.

### Dos defectos encontrados y corregidos

Al probar el perfil `observability` con `docker-compose.prod.yml` (la #59 lo dejó sin verificar):

1. **Prometheus no recogía nada.** Por defecto apunta a `host.docker.internal:8080/8081`, puertos que la configuración
   pública ya no publica: ambos objetivos aparecían `down`. El override usa ahora `prometheus.compose.yml` (por nombre
   de servicio): API y worker `up`.
2. **Grafana dejaba leer sin iniciar sesión.** Heredaba el acceso anónimo de solo lectura del modo local, de modo que
   `GRAFANA_PASSWORD` no protegía el dashboard. El override lo desactiva: sin credenciales y con `admin/admin` → 401; con
   la contraseña generada → 200. Grafana sigue en `127.0.0.1:3300`, sin pasar por Caddy.

## No verificado

- **Un entorno alojado**: no existe. Tampoco Linux, ni `amd64`, ni un certificado real de Let's Encrypt.
- **Cargas mayores** que 60 CSV de 1,35 MB, o una duración larga (fugas de memoria, crecimiento de `jobs`, disco).
- **La alarma de memoria de RabbitMQ** y qué pasa con un OOM real de cada servicio (solo se comprobó que no ocurre en
  esta carga).
- La cuota de 60 envíos por minuto **global** tras el proxy (H4, [revisión de seguridad](../security/upload-api-review.md)):
  las 60 subidas de cada tanda dieron 201, pero no se provocó ni se comprobó el rechazo (429).
- El tiempo de construcción de las imágenes en una máquina de 2 núcleos.
