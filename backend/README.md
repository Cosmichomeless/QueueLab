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

## Mensajería (RabbitMQ)

El contrato vive en `core` (`com.queuelab.core.messaging`), así que API y worker comparten
exactamente el mismo formato y los mismos nombres, sin duplicar lógica.

| Elemento | Valor |
|---|---|
| Exchange | `queuelab.jobs` (direct, durable) |
| Cola | `queuelab.jobs.queued` (durable) |
| Routing key | `job.queued` |
| Dead-letter | exchange `queuelab.jobs.dlx` → cola `queuelab.jobs.queued.dlq` (routing key `job.queued.dead`) |

**Mensaje** (`JobMessage`, `application/json`, persistente): `{"version":1,"jobId":"<uuid>"}`. Solo
lleva el id: el worker carga el resto de PostgreSQL, que es la fuente de verdad. `version` permite
evolucionar el contrato; los campos desconocidos se ignoran y una versión no soportada o un cuerpo
inválido lanza `MalformedJobMessageException`. `JobMessageCodec` es el único sitio que
serializa y valida.

**Cómo se declara la topología.** `JobMessagingConfiguration` (importada por la API y por el
worker) registra los `Declarables`. El `RabbitAdmin` que autoconfigura Spring Boot los declara al
abrir la primera conexión y en cada reconexión. La declaración es idempotente, así que da igual
quién llegue primero. El worker abre conexión al arrancar (su consumidor, #17); la API la abre al
publicar (#16). La API **no** exige RabbitMQ para arrancar ni para aceptar trabajos, y por eso su
indicador de salud de RabbitMQ está desactivado.

Un mensaje rechazado sin reencolar (p. ej. uno malformado) pasa a `queuelab.jobs.queued.dlq` en vez de
bloquear la cola principal.

## Outbox transaccional

Publicar en RabbitMQ y escribir en PostgreSQL no pueden compartir transacción. Para no perder
trabajos ni publicar fantasmas, `POST /api/v1/jobs` guarda **en la misma transacción** el trabajo
(`jobs`) y un evento `JOB_QUEUED` en la tabla `outbox_events` (migración `V4`). Si algo falla, no queda
ni trabajo ni evento; si confirma, quedan los dos.

| Columna | Significado |
|---|---|
| `id` | identificador del evento |
| `job_id` | trabajo al que pertenece (FK con `ON DELETE CASCADE`) |
| `event_type` | `JOB_QUEUED` |
| `payload` | `jsonb` con el contrato de `JobMessage` (`{"version":1,"jobId":"<uuid>"}`) |
| `created_at` | cuándo se creó |
| `published_at` | `NULL` = pendiente de publicar; con valor = ya confirmado por el broker |
| `attempts`, `last_error` | reintentos y último error (los rellenará el despachador, #16) |

Un índice parcial (`WHERE published_at IS NULL`) hace barata la consulta de pendientes. La migración
`V5` crea un evento para los trabajos que ya estuvieran en `QUEUED` antes del outbox, de modo que no
queda ningún trabajo `QUEUED` sin evento publicable.

### Despacho del outbox a RabbitMQ

`OutboxDispatcher` (API) publica los eventos pendientes en `queuelab.jobs` con la routing key
`job.queued`, usando *publisher confirms* (`publisher-confirm-type: correlated`) y mensajes
obligatorios (`mandatory`, `publisher-returns`). `OutboxDispatchScheduler` lo lanza cada segundo.

- **Confirmado** por el broker → `published_at` se rellena y `last_error` se limpia.
- **Sin confirmar** (RabbitMQ caído, timeout, `nack`, mensaje no enrutable) → el evento sigue pendiente,
  `attempts` se incrementa y `last_error` guarda el motivo (máx. 500 caracteres, sin datos del trabajo).
  Si el broker no responde se corta la pasada para no esperar un timeout por evento.
- Las filas se leen con `FOR UPDATE SKIP LOCKED`: varias instancias de la API no publican el mismo evento.
- Entrega **al menos una vez**: si el proceso cae justo tras la confirmación, el mensaje se republica.
  El consumidor debe tolerar duplicados.

| Propiedad | Por defecto | Significado |
|---|---|---|
| `queuelab.outbox.dispatch.enabled` | `true` | apaga el planificador (los tests lo desactivan) |
| `queuelab.outbox.dispatch.interval` | `1s` | pausa entre pasadas |
| `queuelab.outbox.batch-size` | `50` | eventos por pasada |
| `queuelab.outbox.confirm-timeout` | `5s` | espera máxima de la confirmación |

## Worker: consumo de trabajos

El worker (`backend/worker`) consume `queuelab.jobs.queued` con un `@RabbitListener` (`JobConsumer`):

1. Decodifica y valida el mensaje con `JobMessageCodec` (el mismo contrato que la API).
2. `JobProcessor` carga el trabajo de PostgreSQL por su id (la fuente de verdad). Si ya no está en
   `QUEUED` (mensaje duplicado, entrega repetida) lo ignora y confirma el mensaje.
3. Pasa a `RUNNING` con una actualización condicional (`UPDATE ... WHERE status = 'QUEUED'`), de modo que
   dos workers no ejecutan el mismo trabajo, y fija `started_at`.
4. Ejecuta el `JobExecutor` (por ahora `NoopJobExecutor`) y guarda el estado terminal:
   - Éxito → `COMPLETED`, con `finished_at` y `result` (resumen de hasta 1000 caracteres).
   - `JobExecutionException` (fallo esperado) → `FAILED`, con su mensaje como `error` (hasta 500).
   - Cualquier otra excepción → `FAILED` con el texto genérico «Error inesperado durante la
     ejecución»; el detalle (que podría incluir cadenas de conexión o datos internos) solo va al log.
   El resultado queda persistido, así que el mensaje se confirma: no va a la DLQ ni se reentrega.
5. Mensaje malformado (no es JSON, versión no soportada, `jobId` inválido) o trabajo inexistente →
   `AmqpRejectAndDontRequeueException`: RabbitMQ lo desvía a `queuelab.jobs.queued.dlq` y los demás
   mensajes siguen su curso. `default-requeue-rejected: false` evita reencolados en bucle.

`GET /api/v1/jobs/{id}` ya refleja estos cambios porque la API lee de la misma tabla: `status`,
`startedAt`, `finishedAt`, `result` y `error` (`null` mientras no apliquen). La migración `V6` añade las
columnas `result` y `error`.

El worker solo **lee** de PostgreSQL con el esquema que migra la API (no incluye Flyway; los tests lo
crean con Flyway). Arranque local: `SPRING_PROFILES_ACTIVE=local java -jar worker/target/queuelab-worker-0.1.0-SNAPSHOT.jar`.
