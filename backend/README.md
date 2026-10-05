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
| `POST /api/v1/jobs` | Crea un trabajo `{"type": "..."}` en `QUEUED`. 201 con el trabajo y `Location`; no espera al procesamiento. Admite `Idempotency-Key` (ver abajo). |
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
| `Idempotency-Key` vacía, de más de 255 caracteres o con espacios/no ASCII | 400 indicando la cabecera |
| `Idempotency-Key` ya usada con otra carga | 409 «Conflicto de idempotencia» |
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

### Envío idempotente (`Idempotency-Key`)

`POST /api/v1/jobs` acepta la cabecera opcional `Idempotency-Key` (1–255 caracteres ASCII
imprimibles, sin espacios) para que un cliente pueda reintentar el envío sin duplicar trabajo:

- **Primera petición con la clave**: crea el trabajo y su evento de outbox, 201.
- **Misma clave y misma carga**: no crea nada y devuelve el mismo trabajo con su estado actual,
  `200`, `Location` y la cabecera `Idempotent-Replayed: true`.
- **Misma clave con otra carga**: `409` «Conflicto de idempotencia»; no se crea ningún trabajo.
- **Sin cabecera**: cada petición crea un trabajo, como antes.

La clave tiene alcance global (no hay usuarios todavía). Se guarda en `jobs.idempotency_key`
junto con una huella SHA-256 de la carga (`request_fingerprint`, migración V7); un índice único
parcial hace que dos peticiones simultáneas con la misma clave produzcan un solo trabajo
(`INSERT ... ON CONFLICT DO NOTHING` y relectura del ganador). Hoy la huella cubre solo `type`;
la subida de CSV (#29) añadirá el hash del archivo. La validación del cuerpo va antes que la
búsqueda de la clave.

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

`OutboxDispatcher` (API) publica los eventos pendientes según su tipo (`JOB_QUEUED` → `queuelab.jobs` con
la routing key `job.queued`; `JOB_DEAD_LETTERED` → la DLX, ver más abajo), usando *publisher confirms* (`publisher-confirm-type: correlated`) y mensajes
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
2. `JobProcessor` **reclama** el trabajo con `JobRepository.claim`: una sola sentencia
   (`UPDATE ... SET status = 'RUNNING', attempts = attempts + 1 ... WHERE id = ? AND status = 'QUEUED'
   RETURNING *`) que fija `started_at` y devuelve el trabajo con su número de intento. PostgreSQL
   serializa los `UPDATE` sobre la misma fila, así que si dos workers reciben el mismo mensaje solo uno
   obtiene el trabajo.
3. Si el reclamo no devuelve nada (entrega duplicada, otro worker lo tomó, ya terminó) el mensaje se
   confirma sin ejecutar nada: no se duplican efectos. Si el trabajo ni siquiera existe, va a la DLQ.
4. Ejecuta el `JobExecutor` (por ahora `NoopJobExecutor`) y guarda el estado terminal:
   - Éxito → `COMPLETED`, con `finished_at` y `result` (resumen de hasta 1000 caracteres).
   - `JobExecutionException` (fallo esperado) → `FAILED`, con su mensaje como `error` (hasta 500).
   - Cualquier otra excepción → `FAILED` con el texto genérico «Error inesperado durante la
     ejecución»; el detalle (que podría incluir cadenas de conexión o datos internos) solo va al log.
   El cierre (`JobRepository.finishAttempt`) exige `status = 'RUNNING'` **y** el mismo número de
   intento, de modo que un worker rezagado cuyo intento ya fue relevado no pisa el resultado del nuevo.
   El resultado queda persistido, así que el mensaje se confirma: no va a la DLQ ni se reentrega.
5. Mensaje malformado (no es JSON, versión no soportada, `jobId` inválido) o trabajo inexistente →
   `AmqpRejectAndDontRequeueException`: RabbitMQ lo desvía a `queuelab.jobs.queued.dlq` y los demás
   mensajes siguen su curso. `default-requeue-rejected: false` evita reencolados en bucle.

`GET /api/v1/jobs/{id}` ya refleja estos cambios porque la API lee de la misma tabla: `status`,
`startedAt`, `finishedAt`, `result` y `error` (`null` mientras no apliquen). La migración `V6` añade las
columnas `result` y `error`; `V8` añade `attempts` (intentos reclamados, 0 hasta que un worker toma el
trabajo) y `V9` `outbox_events.available_at` (publicación diferida).

El worker solo **escribe** en `jobs` y `outbox_events`, con el esquema que migra la API (no incluye Flyway; los tests lo
crean con Flyway). Arranque local: `SPRING_PROFILES_ACTIVE=local java -jar worker/target/queuelab-worker-0.1.0-SNAPSHOT.jar`.

### Reintentos con espera exponencial

Solo se reintenta lo que el ejecutor declara **transitorio** lanzando `TransientJobException`
(subclase de `JobExecutionException`). Un `JobExecutionException` corriente es permanente y cualquier
otra excepción es un error inesperado: ambos acaban en `FAILED` en el primer intento, así que un fallo
permanente nunca entra en bucle.

Ante un fallo transitorio, `JobProcessor`:

1. Si quedan intentos (`attempts < max-attempts`): en **una transacción** pasa el trabajo a `RETRYING`
   (conserva el resumen del último `error`, `finished_at` sigue vacío) e inserta un evento
   `JOB_QUEUED` en el outbox con `available_at = ahora + espera`. El dispatcher de la API no
   publica un evento hasta que `available_at` vence (`V9`), y el worker reclama el trabajo desde
   `RETRYING` como lo haría desde `QUEUED`.
2. Si ya no quedan: `FAILED` con el último error y sin evento nuevo.

La espera tras el intento *n* es `initial-delay × multiplier^(n-1)`, acotada por `max-delay`
(sin jitter). Configuración del worker:

| Propiedad | Por defecto | Significado |
|---|---|---|
| `queuelab.worker.retry.max-attempts` | `3` | Ejecuciones totales, la primera incluida |
| `queuelab.worker.retry.initial-delay` | `5s` | Espera tras el primer fallo |
| `queuelab.worker.retry.multiplier` | `2.0` | Factor de crecimiento (≥ 1) |
| `queuelab.worker.retry.max-delay` | `5m` | Tope de la espera |

Con los valores por defecto: intento 1 → 5 s → intento 2 → 10 s → intento 3 → `FAILED`.
La API expone `status` (`RETRYING`) y `attempts` (nº de intento en curso o del último realizado).

### Cola dead-letter: trabajos con reintentos agotados

Cuando un trabajo agota `max-attempts`, el worker lo deja en `FAILED` y, **en la misma transacción**,
inserta un evento `JOB_DEAD_LETTERED` en el outbox. El `OutboxDispatcher` de la API lo publica (con
publisher confirms, como el resto) en `queuelab.jobs.dlx` con la routing key `job.queued.dead`, y
termina en `queuelab.jobs.queued.dlq`. No puede haber un `FAILED` por agotamiento sin su aviso en la
DLQ, ni al revés. Los fallos permanentes o inesperados (que no se reintentan) **no** van a la DLQ; sí
van los mensajes malformados o de trabajos inexistentes, rechazados por el consumidor.

Mensaje de agotamiento (`application/json`; es un `JobMessage` ampliado):

```json
{"version":1,"jobId":"2f6c0d52-6f0e-4a29-9d3a-1f2b7a7b3f10","attempts":3,"cause":"El servicio externo no responde"}
```

Solo lleva el id, el número de ejecuciones y el mismo resumen de error que expone la API: nunca datos
de entrada del trabajo. La misma información se consulta sin tocar la cola con
`GET /api/v1/jobs/{id}` (`status: FAILED`, `attempts`, `error`) o `GET /api/v1/jobs?status=FAILED`.
Para inspeccionar la cola: `docker compose exec rabbitmq rabbitmqctl list_queues name messages` o la
consola de gestión en http://localhost:15672 (`queuelab`/`queuelab`).

## Prueba de extremo a extremo (`e2e`)

El módulo `e2e` verifica el primer flujo asíncrono completo sin atajos. `EnqueueToCompletionTest` arranca
PostgreSQL y RabbitMQ con Testcontainers y lanza los **jars reales** de la API y del worker como procesos
separados (la API migra el esquema; el worker se lanza después). Todo se observa por HTTP y SQL:

1. `POST /api/v1/jobs` → `201` en `QUEUED`; el worker lo deja en `COMPLETED` con `result`, y el evento del
   outbox queda publicado.
2. Con RabbitMQ parado (el contenedor se detiene de verdad), la API sigue aceptando el trabajo, que queda en
   `QUEUED` con su evento de outbox pendiente y `attempts` en aumento. Al volver el broker, el
   despachador lo publica y el worker lo completa.

Los logs de cada proceso quedan en `e2e/target/e2e-logs/`. El módulo va el último del reactor porque necesita
los jars empaquetados: ejecuta `./mvnw verify` (o `./mvnw -pl e2e -am verify`); lanzar solo `test` falla con un
mensaje que lo explica. Requiere Docker.
