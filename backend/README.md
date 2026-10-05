# QueueLab backend

Monorepo Maven con dos procesos independientes:

| Módulo | Artefacto | Rol |
|---|---|---|
| `api` | `queuelab-api` | API REST (HTTP, health check en `/actuator/health`) |
| `worker` | `queuelab-worker` | Proceso de fondo sin servidor HTTP que consume trabajos |

## Compilar API y worker

Requiere JDK 25. Un solo comando compila y prueba ambos módulos:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS
cd backend && ./mvnw verify
```

## Arrancar

```bash
java -jar backend/api/target/queuelab-api-0.1.0-SNAPSHOT.jar
java -jar backend/worker/target/queuelab-worker-0.1.0-SNAPSHOT.jar
```

## Servicios locales (PostgreSQL y RabbitMQ)

Se arrancan con Docker Compose desde la raíz del repositorio:

```bash
docker compose up -d --wait      # arranca y espera a que estén healthy
docker compose ps                # estado
docker compose down              # parar (conserva los datos)
docker compose down -v           # parar y borrar los volúmenes
```

| Servicio | Host | Puerto | Credenciales por defecto (solo desarrollo) |
|---|---|---|---|
| PostgreSQL 18 | `localhost` | `5434` | `queuelab` / `queuelab`, base `queuelab` |
| RabbitMQ 4 (AMQP) | `localhost` | `5672` | `queuelab` / `queuelab` |
| RabbitMQ Management | http://localhost:15672 | `15672` | `queuelab` / `queuelab` |

PostgreSQL usa el puerto 5434 del host para no chocar con otras instancias locales
(5432/5433). Los puertos y credenciales se pueden cambiar con variables de entorno
(`POSTGRES_PORT`, `POSTGRES_PASSWORD`, `RABBITMQ_PORT`…).

## Configuración de API y worker

Ambos procesos leen las URLs y credenciales de variables de entorno
(ninguna contraseña está escrita en el código):

| Variable | Por defecto | Uso |
|---|---|---|
| `QUEUELAB_DB_URL` | `jdbc:postgresql://localhost:5434/queuelab` | URL JDBC de PostgreSQL |
| `QUEUELAB_DB_USER` | `queuelab` | Usuario de PostgreSQL |
| `QUEUELAB_DB_PASSWORD` | _(vacío)_ | Contraseña de PostgreSQL |
| `QUEUELAB_RABBITMQ_HOST` | `localhost` | Host de RabbitMQ |
| `QUEUELAB_RABBITMQ_PORT` | `5672` | Puerto AMQP |
| `QUEUELAB_RABBITMQ_USER` | `queuelab` | Usuario de RabbitMQ |
| `QUEUELAB_RABBITMQ_PASSWORD` | _(vacío)_ | Contraseña de RabbitMQ |

### Perfiles y ficheros de ejemplo

- Todas las variables (Compose, API, worker y dashboard) están explicadas en
  [`.env.example`](../.env.example); cópielo a `.env` (ignorado por Git, igual que
  `.env.local` y `.env.*.local`). El dashboard tiene el suyo en
  [`frontend/.env.example`](../frontend/.env.example).
- El perfil de Spring **`local`** (`application-local.yml` en API y worker) aporta las
  contraseñas de desarrollo de Docker Compose si no se definen. Sin ese perfil las
  contraseñas están vacías, así que en cualquier otro entorno hay que definirlas.

Ejemplo con los servicios locales:

```bash
SPRING_PROFILES_ACTIVE=local java -jar backend/api/target/queuelab-api-0.1.0-SNAPSHOT.jar
```

## Migraciones de base de datos (Flyway)

El esquema vive en `backend/core/src/main/resources/db/migration` (`V<n>__<descripcion>.sql`).
La **API** aplica las migraciones pendientes al arrancar; el worker no las ejecuta.

- Una base vacía aplica `V1__baseline.sql`; en el siguiente arranque Flyway informa
  `Schema "public" is up to date` y no repite nada.
- Nunca se edita una migración ya aplicada: los cambios van en una versión nueva.
- Las pruebas de la API levantan un PostgreSQL desechable con Testcontainers
  (requieren Docker en marcha).

## Modelo de trabajos (`core`)

La tabla `jobs` (migración `V2__create_jobs.sql`) guarda `id` (UUID), `type`, `status` y los
timestamps `created_at`, `updated_at`, `started_at` y `finished_at`. Una restricción `CHECK`
garantiza que el estado sea uno de los cinco conocidos.

Las transiciones se validan en la lógica de aplicación (`com.queuelab.core.job.JobStatus`
y `Job.transitionTo`), que lanza `InvalidJobTransitionException` si no están permitidas:

| Desde | Hacia |
|---|---|
| `QUEUED` | `RUNNING` |
| `RUNNING` | `COMPLETED`, `FAILED`, `RETRYING` |
| `RETRYING` | `RUNNING` |
| `COMPLETED`, `FAILED` | _(terminales)_ |

`started_at` es la primera vez que el trabajo pasó a `RUNNING` (no cambia en reintentos) y
`finished_at` se fija al llegar a un estado terminal. `JobRepository.update(job, expectedStatus)`
guarda el cambio solo si el estado en base de datos sigue siendo el esperado, para que dos
procesos no se pisen.

## API de trabajos

| Método y ruta | Descripción |
|---|---|
| `POST /api/v1/jobs` | Crea un trabajo `{"type": "..."}` en `QUEUED`. 201 con el trabajo y `Location`; no espera al procesamiento. |
| `GET /api/v1/jobs/{id}` | Detalle de un trabajo. 404 si no existe. |
| `GET /api/v1/jobs` | Listado paginado, del más reciente al más antiguo. |

Los errores usan `application/problem+json` (RFC 9457) con `status`, `title`, `detail` e `instance`.

### Validación y errores

`type` es obligatorio, de hasta 100 caracteres, en `kebab-case` (minúsculas, dígitos y guiones) y
debe ser uno de los tipos conocidos, que se configuran en `queuelab.jobs.types` (por defecto
`noop`, `csv-import`, `image-resize`). Cualquier incumplimiento da 400.

| Situación | Respuesta |
|---|---|
| `type` ausente, vacío, mal formado o desconocido | 400 «Petición no válida» (el mensaje lista los tipos admitidos) |
| Cuerpo ausente o JSON ilegible | 400, sin reflejar el mensaje del parser |
| Id, `status` o `limit` con formato incorrecto | 400 indicando el parámetro |
| Ruta inexistente, método o `Content-Type` no admitidos | 404 / 405 / 415 con el mismo formato |
| Trabajo inexistente | 404 «Trabajo no encontrado» |
| Fallo no previsto | 500 «Error interno» genérico; la traza solo se escribe en el log |

### Listado paginado

Parámetros opcionales: `status` (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `RETRYING`),
`limit` (1–100, por defecto 20) y `cursor`. La respuesta es
`{"items": [...], "nextCursor": "..."}`; `nextCursor` es `null` en la última página y, si no,
se envía como `cursor` para pedir la siguiente.

La paginación es **por cursor**, no por offset: el orden es total (`created_at` descendente y
`id` como desempate), así que no se repiten ni se saltan trabajos aunque se creen otros mientras
el cliente pagina. El cursor es opaco; un cursor inválido da 400.

## Pruebas del ciclo de vida

`JobLifecycleApiTest` recorre el ciclo completo contra un PostgreSQL real (Testcontainers) cuyo
esquema crea Flyway al arrancar el contexto: creación por API, consulta, transiciones
(`QUEUED` → `RUNNING` → `RETRYING` → `RUNNING` → `COMPLETED`), paginación por cursor sin repetidos ni
saltos, filtro por estado, las 10 transiciones inválidas representativas, el rechazo de escrituras
obsoletas y los errores 404/400. Se ejecutan con `./mvnw verify`; solo hace falta Docker.
