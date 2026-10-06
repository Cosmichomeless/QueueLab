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

## Servicios locales (PostgreSQL, RabbitMQ y Redis)

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
| Redis 7 | `localhost` | `6379` | sin contraseña; sin persistencia (solo guarda contadores con expiración) |

PostgreSQL usa el puerto 5434 del host para no chocar con otras instancias locales
(5432/5433). Los puertos y credenciales se pueden cambiar con variables de entorno
(`POSTGRES_PORT`, `POSTGRES_PASSWORD`, `RABBITMQ_PORT`, `REDIS_PORT`…).

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
| `QUEUELAB_REDIS_HOST` | `localhost` | Solo la API. Host de Redis (contadores del límite de envíos) |
| `QUEUELAB_REDIS_PORT` | `6379` | Solo la API. Puerto de Redis |
| `QUEUELAB_REDIS_PASSWORD` | _(vacío)_ | Solo la API. Contraseña de Redis, si la tiene |
| `QUEUELAB_RATE_LIMIT_ENABLED` | `true` | Solo la API. `false` desactiva el límite de envíos (y no se usa Redis) |
| `QUEUELAB_RATE_LIMIT_MAX_REQUESTS` | `60` | Solo la API. Envíos que puede hacer cada cliente (IP) por ventana; mínimo 1 |
| `QUEUELAB_RATE_LIMIT_WINDOW` | `1m` | Solo la API. Duración de la ventana (`30s`, `1m`…); mínimo 1 s |
| `QUEUELAB_STORAGE_DIRECTORY` | `./data/storage` | Directorio del almacenamiento de ficheros (API y worker deben compartirlo; ver [Almacenamiento](#almacenamiento-de-ficheros)) |
| `QUEUELAB_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Solo la API. Orígenes del dashboard que pueden llamarla desde el navegador (CORS, solo en `/api/**`, métodos `GET` y `POST`), separados por comas; vacío desactiva CORS. Cualquier otro origen recibe 403. |
| `QUEUELAB_BACKPRESSURE_MAX_PENDING` | `1000` | Solo la API. Umbral de contrapresión: con tantos trabajos esperando worker los envíos nuevos reciben `503` (ver [Contrapresión](#contrapresión-cola-saturada)). |
| `QUEUELAB_BACKPRESSURE_RETRY_AFTER` | `30s` | Solo la API. Espera sugerida (`Retry-After`) a los envíos rechazados por saturación; mínimo 1 s. |

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

### Índices y planes de consulta

Los índices de `jobs` se justifican con planes medidos sobre 1.000.000 de filas
([`docs/performance/query-plans.md`](../docs/performance/query-plans.md)): el listado paginado, `countWaiting` y la
búsqueda de leases ya usaban índices adecuados (V2, V3, V10); la migración `V13` añade cuatro índices parciales
para la limpieza de ficheros, que pasa de recorrer toda la tabla (23–55 ms) a menos de 0,3 ms. El documento incluye
los pasos para reproducir la comparación (`docs/performance/seed-jobs.sql` y `explain-queries.sql`).

### Benchmark de throughput y latencia

[`docs/performance/benchmark-results.md`](../docs/performance/benchmark-results.md) publica el escenario, el hardware y
las métricas medidas con el script reproducible `docs/performance/benchmark.py`. Resumen en un Apple M4 Pro (12 núcleos)
con CSV de 8,6 MiB: `queuelab.worker.concurrency` de 1 a 8 sube el throughput de 8,3 a 40,2 trabajos/s (4,8×); de 8 a
16 no sube y **duplica la latencia por trabajo** (133 → 272 ms). Para trabajos muy cortos el techo es el outbox
(≈ 49 eventos/s por defecto), no el worker.
Cómo elegir y cambiar los límites con seguridad: [`docs/performance/capacity.md`](../docs/performance/capacity.md).

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
| `FAILED` | `QUEUED` (solo por reintento manual, ver «Reintento manual») |
| `COMPLETED` | _(terminal)_ |

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
| `POST /api/v1/jobs/{id}/retry` | Reintento manual de un trabajo `FAILED` (ver «Reintento manual»). 202 con el trabajo en `QUEUED`; 409 si no es elegible. |
| `GET /api/v1/jobs/{id}/retries` | Historial de reintentos manuales, del más antiguo al más reciente. |
| `GET /api/v1/queue` | Saturación de la cola: `pending`, `maxPending`, `saturated`, `retryAfterSeconds` (ver «Contrapresión»). |

Los errores usan `application/problem+json` (RFC 9457) con `status`, `title`, `detail` e `instance`.

### Validación y errores

`type` es obligatorio, de hasta 100 caracteres, en `kebab-case` (minúsculas, dígitos y guiones) y
debe ser uno de los tipos conocidos, que se configuran en `queuelab.jobs.types` (por defecto
`noop`, `csv-import`). Solo figuran los que el worker sabe ejecutar: un tipo sin implementar (p. ej.
`image-resize`) da 400 en vez de aceptarse y quedarse sin procesar. Cualquier incumplimiento da 400. El alcance del trabajo `csv-import` (formato, límites y salida) está en
[`docs/csv-workload.md`](../docs/csv-workload.md).

| Situación | Respuesta |
|---|---|
| `type` ausente, vacío, mal formado o desconocido | 400 «Petición no válida» (el mensaje lista los tipos admitidos) |
| Cuerpo ausente o JSON ilegible | 400, sin reflejar el mensaje del parser |
| Id, `status` o `limit` con formato incorrecto | 400 indicando el parámetro |
| Ruta inexistente, método o `Content-Type` no admitidos | 404 / 405 / 415 con el mismo formato |
| `Idempotency-Key` vacía, de más de 255 caracteres o con espacios/no ASCII | 400 indicando la cabecera |
| `Idempotency-Key` ya usada con otra carga | 409 «Conflicto de idempotencia» |
| Trabajo inexistente | 404 «Trabajo no encontrado» |
| Cuota de envíos agotada (`POST /jobs`, `POST /jobs/csv`) | 429 «Demasiados envíos» con `Retry-After` (ver «Límite de envíos») |
| Cola saturada (`POST /jobs`, `POST /jobs/csv`, `POST /jobs/{id}/retry`) | 503 «Cola saturada» con `Retry-After` (ver «Contrapresión») |
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

### Subida de CSV (`POST /api/v1/jobs/csv`)

Crea un trabajo `csv-import` a partir de un fichero. Es `multipart/form-data` con la parte `file`:

```bash
curl -i -F "file=@datos.csv;type=text/csv" http://localhost:8080/api/v1/jobs/csv
```

Responde `201` con el trabajo en `QUEUED` y `Location`, sin esperar al procesamiento. El servicio guarda el fichero en
el [almacenamiento](#almacenamiento-de-ficheros) como `inputs/<jobId>.csv` (el nombre del cliente se descarta), apunta
`jobs.input_ref` a esa referencia y escribe trabajo + evento del outbox en una sola transacción.

| Caso | Respuesta |
|---|---|
| CSV válido (`text/csv`, o extensión `.csv` con cualquier `Content-Type`) | `201` |
| Supera `queuelab.csv.max-file-size` (10 MiB) | `413` |
| No se declara como CSV (ni `Content-Type` ni extensión) | `415` |
| Petición que no es `multipart/form-data` | `415` |
| Falta la parte `file`, fichero vacío, primera línea en blanco o demasiado larga (>64 KiB), o no UTF-8 | `400` |

- **Sin huérfanos**: todo lo comprobable se comprueba antes de escribir. El límite de tamaño se aplica dos veces (el
  límite de multipart del contenedor corta la subida y el servicio la vuelve a comprobar), así que un fichero grande
  no llega a guardarse. Si la transacción falla después de guardar el fichero, este se borra. Solo una caída del
  proceso justo entre ambos pasos podría dejar un fichero sin trabajo; la limpieza (`StorageCleaner`) lo recoge como huérfano.
- **Validación mínima en la subida**: tipo declarado, tamaño, no vacío y primera línea UTF-8 no vacía. La cabecera
  completa (nombres, duplicados, nº de columnas) y las filas se validan al procesar (#30/#32), que es donde se conocen
  las reglas de [`docs/csv-workload.md`](../docs/csv-workload.md).
- Esta ruta **no admite `Idempotency-Key`** (habría que leer todo el fichero para la huella); un reenvío crea otro trabajo.
- Configuración: `queuelab.csv.max-file-size` (por defecto `10MB`; `spring.servlet.multipart.max-request-size` queda en `11MB`,
  súbelo también si subes el límite del fichero).

### Resultado y descarga (`GET /api/v1/jobs/{id}/result`)

Cuando un trabajo termina en `COMPLETED` y produjo un fichero (hoy, `csv-import` → `<jobId>.stats.json`), la
respuesta de `GET /api/v1/jobs/{id}`, del listado y de los envíos idempotentes incluye `resultFile`:

```json
"resultFile": {"name": "<jobId>.stats.json", "contentType": "application/json", "size": 196,
               "downloadUrl": "/api/v1/jobs/<jobId>/result"}
```

- `resultFile` es `null` si el trabajo no está `COMPLETED` (pendiente, `RUNNING`, `RETRYING`, `FAILED`), si no
  produce fichero (`noop`) o si el fichero ya no está en el almacenamiento: **no se anuncia un resultado que no
  se pueda descargar**. El listado resuelve las referencias con una sola consulta (`findResultRefs`).
- `GET /api/v1/jobs/{id}/result` envía el fichero en streaming con `Content-Type`, `Content-Length` y
  `Content-Disposition: attachment`. Errores `application/problem+json`: `404` si el trabajo no existe o
  terminó sin fichero («Resultado no encontrado»); `409` si aún no terminó o falló («Resultado no disponible»).

### Contrapresión (cola saturada)

Sin límite, una avalancha de envíos llena la cola más rápido de lo que los workers la vacían: cada trabajo nuevo
espera más y la latencia crece sin tope. La API se protege rechazando trabajo nuevo cuando ya hay demasiado esperando.

- **Medida de saturación**: trabajos **pendientes** = los que están en `QUEUED` o `RETRYING` (aceptados y todavía sin
  worker). Los `RUNNING` no cuentan porque ya tienen capacidad asignada, y los terminados tampoco.
- **Umbral**: `queuelab.backpressure.max-pending` (por defecto **1000**; mínimo 1). Con `pending >= max-pending`
  la cola está saturada.
- **Respuesta**: `503 Service Unavailable` con `Retry-After: <segundos>` (`queuelab.backpressure.retry-after`,
  por defecto 30 s) y un `application/problem+json` con la medida que disparó el rechazo:

  ```json
  {"title":"Cola saturada","status":503,"detail":"La cola está saturada (3 trabajos pendientes de un máximo de 3). Inténtalo de nuevo en 12 s.",
   "pending":3,"maxPending":3,"retryAfterSeconds":12}
  ```

  Se usa 503 y no 429 porque el límite es de capacidad del sistema, no de un cliente concreto; la cuota por cliente
  es el 429 de «Límite de envíos».
- **Qué se comprueba**: lo que encola trabajo nuevo: `POST /api/v1/jobs`, `POST /api/v1/jobs/csv` (antes de guardar
  el fichero, para no gastar disco en lo que se va a rechazar) y `POST /api/v1/jobs/{id}/retry`. Repetir una
  `Idempotency-Key` ya aceptada no añade carga y sigue respondiendo `200`. Las lecturas nunca se rechazan.
  Un rechazo no guarda nada: ni trabajo, ni evento de outbox, ni fichero.
- **Medirlo**: `GET /api/v1/queue` devuelve `{"pending":2,"maxPending":3,"saturated":false,"retryAfterSeconds":7}`.
  `pending` se cuenta solo hasta `max-pending` (consulta acotada con `LIMIT`, su coste no crece con la cola), así
  que nunca lo supera.
- **Es un umbral blando**: con envíos simultáneos justo en el límite pueden colarse unos pocos trabajos de más. Es
  deliberado: serializar los envíos para evitarlo costaría más que lo que protege.
- **Cómo elegir el valor**: el umbral es `capacidad × espera tolerable`. Con `N` workers de concurrencia `c` y un
  trabajo de `t` segundos, la cola se vacía a `N·c/t` trabajos por segundo; un `max-pending` de `W·N·c/t` acota la espera
  en cola a `W` segundos. Ejemplo: 2 workers × 2 consumidores, trabajos de 5 s → 0,8 trabajos/s; para esperar como
  mucho 5 min (300 s) el umbral sería ≈ 240.
- Un cliente bien portado respeta `Retry-After` y reintenta; el dashboard muestra el error de la API tal cual.
- `BackpressureTest` cubren: bajo umbral, en el umbral (503, cabecera y cuerpo, nada guardado),
  qué estados cuentan, CSV sin fichero huérfano, idempotencia, reintento manual, el endpoint y la validación de la
  configuración.

### Límite de envíos (cuota por cliente)

Complementa a la contrapresión: ésta protege al sistema de la **cola** (503, global); la cuota protege de **un cliente**
que envía demasiado deprisa (429, por cliente). Se cuenta en Redis para que valga lo mismo con una o con varias
instancias de la API detrás de un balanceador.

- **Qué se limita**: los envíos, `POST /api/v1/jobs` y `POST /api/v1/jobs/csv`, que **comparten** la misma cuota.
  Las lecturas, `GET /api/v1/queue`, `retry` y `actuator` no cuentan. Se aplica antes que ninguna otra comprobación
  (también que la contrapresión), de modo que un envío rechazado no se valida, no se guarda ni se encola. Una
  repetición con `Idempotency-Key` sí cuenta: sigue siendo una petición del cliente.
- **Quién es «un cliente»**: la IP remota de la conexión. Detrás de un proxy o balanceador hay que activar
  `server.forward-headers-strategy` (`native` o `framework`) para que sea la del cliente real; si no, todos los
  clientes compartirían la cuota del proxy. No hay autenticación en la API, así que la IP es lo único que la
  identifica (clientes tras una misma NAT comparten cuota).
- **Cuota**: `queuelab.rate-limit.max-requests` envíos (por defecto **60**) por `queuelab.rate-limit.window`
  (por defecto **1 m**). Es una **ventana fija**: la primera petición abre la ventana y el contador se borra solo al
  terminar. Permite una ráfaga de hasta `max-requests` al final de una ventana y otra al principio de la siguiente
  (hasta el doble en un instante); es el precio de un contador sencillo y barato.
- **Respuesta al exceder**: `429 Too Many Requests` con `Retry-After: <segundos hasta que se reinicie la ventana>` y
  un `application/problem+json`:

  ```json
  {"title":"Demasiados envíos","status":429,"detail":"Has superado el límite de 5 envíos cada 60 s. Inténtalo de nuevo en 41 s.",
   "limit":5,"windowSeconds":60,"retryAfterSeconds":41}
  ```

  Todas las respuestas de envío (también las `201`) llevan `RateLimit-Limit`, `RateLimit-Remaining` y
  `RateLimit-Reset` (segundos), para que un cliente se autorregule sin esperar al 429. El dashboard puede leerlas
  también desde el navegador (están en las cabeceras expuestas de CORS).
- **Compartida entre instancias**: la clave `queuelab:ratelimit:submit:<ip>` vive en Redis. Incrementar y fijar la
  expiración ocurren en **un solo script Lua**, así que son atómicos aunque varias instancias cuenten a la vez, y el
  tiempo de la ventana lo mide Redis (no los relojes de las instancias).
- **Si Redis no responde**: la petición **se deja pasar** (*fail-open*) y se escribe un `WARN` en el log
  (`Redis no responde: se omite el límite de envíos…`). Es coherente con que la API acepte trabajos sin RabbitMQ: la
  cuota es una protección, no una condición de corrección, y `/actuator/health` no depende de Redis. Coste: con
  Redis caído cada envío espera hasta el timeout (500 ms, `spring.data.redis.timeout`). Para no usar Redis hay que
  poner `QUEUELAB_RATE_LIMIT_ENABLED=false`. Reiniciar Redis (sin persistencia) reinicia las cuotas.
- **Cómo elegir el valor**: la cuota por cliente × el número de clientes activos debe quedar por debajo de lo que
  el sistema absorbe; si no, quien protege es la contrapresión (503). Un uso interactivo del dashboard no pasa de
  unos pocos envíos por minuto; los valores por defecto (60/min) dejan margen a un script razonable.
- **Probarlo a mano**: `docker compose up -d redis`, dos API en puertos distintos con
  `QUEUELAB_RATE_LIMIT_MAX_REQUESTS=5 QUEUELAB_RATE_LIMIT_WINDOW=60s` y alternar `curl -X POST …/api/v1/jobs` entre
  ambas: el sexto envío, vaya a la instancia que vaya, recibe 429.
- Pruebas (Redis real con Testcontainers): `RateLimitTest` (429 con `Retry-After` y cabeceras, nada guardado, CSV y
  JSON comparten cuota, clientes independientes, lecturas libres) y `SubmissionRateLimiterTest` (ventana, reinicio al
  expirar, cuota compartida entre dos instancias con conexiones independientes, concurrencia sin pasarse de la cuota,
  Redis inaccesible y validación de la configuración).

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
4. Ejecuta el `JobExecutor` (`TypeRoutingJobExecutor`, que delega en el `JobHandler` del tipo del trabajo: `noop` y
   `csv-import`; un tipo sin manejador es un fallo permanente) y guarda el estado terminal:
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

### Concurrencia del worker

Cada proceso worker ejecuta como máximo `queuelab.worker.concurrency` trabajos **a la vez**. Es un número fijo de
consumidores (`concurrentConsumers == maxConcurrentConsumers`): el contenedor de Spring AMQP no escala por su cuenta,
así que el límite no se supera aunque la cola esté llena. La capacidad total del sistema es ese valor por el número de
workers en marcha.

| Propiedad (variable) | Por defecto | Significado |
|---|---|---|
| `queuelab.worker.concurrency` (`QUEUELAB_WORKER_CONCURRENCY`) | `2` | Consumidores simultáneos por proceso, entre 1 y 64. Fuera de rango, el worker no arranca y lo explica. |
| `queuelab.worker.prefetch` (`QUEUELAB_WORKER_PREFETCH`) | `1` | Mensajes sin confirmar que RabbitMQ entrega a cada consumidor. Con `1`, un worker ocupado no acapara mensajes que otro libre podría procesar. |

Por qué esos valores: el trabajo típico (CSV) es de CPU y E/S moderadas y cada uno mantiene un lease y conexiones a
PostgreSQL, así que un valor pequeño es seguro en un equipo modesto; Spring AMQP, sin configurar, usaría 1 consumidor con
`prefetch` 250, que serializa la ejecución y deja 250 mensajes retenidos por un solo proceso. Para subir el límite:

- Cada trabajo en curso puede usar una conexión del pool de PostgreSQL (Hikari, 10 por defecto): no pongas más
  consumidores que conexiones libres.
- El latido del lease usa un único hilo y consultas muy cortas: no es un cuello de botella con decenas de consumidores.
- Súbelo gradualmente y observa la latencia en cola y el uso de CPU/memoria antes de pasar al siguiente escalón.

`ConcurrencyTest` lo comprueba contra RabbitMQ y PostgreSQL reales: con `concurrency=3` y 12 trabajos de 300 ms
publicados a la vez, el máximo simultáneo observado es exactamente 3 y los 12 terminan `COMPLETED`.

### Procesamiento de CSV (`csv-import`)

`CsvImportJobHandler` procesa el fichero que subió `POST /api/v1/jobs/csv` (contrato completo en
[`docs/csv-workload.md`](../docs/csv-workload.md)):

1. Lee `jobs.input_ref` y abre el fichero con `FileStorage.open`.
2. Lo decodifica como UTF-8 **estricto** (bytes inválidos = error, no `?`) tolerando un BOM inicial.
3. `CsvRecordReader` (RFC 4180, estricto) devuelve un registro cada vez; `ColumnStats` acumula por columna
   recuentos, `min/max/sum` (con `BigDecimal`, exacto y determinista) y longitudes. **La memoria es O(columnas)**:
   nunca hay más de una fila en memoria, y un campo se limita a 1 MiB para que unas comillas sin cerrar no
   agoten el heap.
4. Guarda `results/<jobId>.stats.json` (`FileStorage.store`, que reescribe si es un reintento), anota su
   referencia en `jobs.result_ref` y deja `result` = `CSV procesado: N filas, M columnas`.

| Situación | Resultado |
|---|---|
| Cabecera inválida (vacía, nombre vacío/duplicado/>100 caracteres, >100 columnas), fila con otro nº de campos, comillas mal cerradas, UTF-8 inválido, trabajo sin fichero | `FAILED` en el primer intento, sin reintentos. El mensaje lleva la línea y el motivo, nunca el contenido de la fila (p. ej. `Línea 3: tiene 3 campos y la cabecera 2`). |
| Fichero de entrada ausente, error de E/S al leer o al guardar las estadísticas | Transitorio (`TransientJobException`): `RETRYING` con espera exponencial y DLQ al agotar los intentos. |

Las líneas vacías al final se ignoran; una línea vacía en mitad solo es válida si el CSV tiene una columna (es un valor
vacío). Verificado en real con un CSV de 9,8 MB (323.074 filas) con el worker limitado a `-Xmx80m`.

### Limpieza de ficheros (`StorageCleaner`)

El worker borra periódicamente los ficheros que ya no hacen falta, según la política de retención de
[`docs/csv-workload.md`](../docs/csv-workload.md#política-de-retención-32): entradas de trabajos `COMPLETED` y `FAILED`,
resultados antiguos, temporales `.tmp-*` que dejó una escritura interrumpida (`FileStorage.purgeTemporaries`) y ficheros huérfanos (sin ningún trabajo que apunte a ellos; `FileStorage.listReferences` + `JobRepository.isReferenced`). Cada pasada recorre los directorios `inputs/` y `results/`, algo asumible en almacenamiento local.
Solo mira trabajos en estado terminal; la referencia en `jobs` se anula antes de borrar el fichero (y se restaura si el
borrado falla), de modo que un reintento manual concurrente no pierde su entrada.

| Propiedad | Por defecto | Significado |
|---|---|---|
| `queuelab.cleanup.enabled` | `true` | apaga el planificador (los tests lo desactivan y llaman a `cleanOnce()`) |
| `queuelab.cleanup.interval` / `initial-delay` | `10m` / `1m` | pausa entre pasadas / espera tras arrancar |
| `queuelab.cleanup.completed-input-retention` | `1h` | cuánto se conserva la entrada de un trabajo `COMPLETED` |
| `queuelab.cleanup.failed-input-retention` | `7d` | ídem para `FAILED` (permite el reintento manual) |
| `queuelab.cleanup.result-retention` | `30d` | cuánto se conserva el resultado descargable |
| `queuelab.cleanup.temporary-retention` | `1h` | antigüedad mínima de un temporal o de un fichero sin trabajo para considerarlo abandonado |
| `queuelab.cleanup.batch-size` | `100` | ficheros por pasada y tipo |

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

### Recuperación de trabajos interrumpidos (lease)

Si un worker muere (o se queda colgado) tras reclamar un trabajo, este quedaría en `RUNNING` para
siempre: el mensaje ya se confirmó y nadie volvería a tomarlo. Para detectarlo, cada trabajo en
ejecución tiene un **lease** (`jobs.lease_expires_at`, migración `V10`):

- `claim` fija el lease a `ahora + lease.duration`; `finishAttempt` lo borra (`NULL`).
- Mientras `executor.execute` corre, el worker lo **renueva** cada `duration / 3` (latido en un hilo
  daemon, `JobRepository.renewLease`, que exige `RUNNING` y el mismo intento). Un trabajo largo pero vivo
  nunca vence; caben dos latidos perdidos antes de que venza.
- `AbandonedJobRecoverer` (lanzado por `RecoveryScheduler` cada `recovery.interval`) busca los `RUNNING`
  con `lease_expires_at <= now()` (índice parcial `jobs_running_lease_idx`) y aplica la **política de
  reintentos**: el intento perdido cuenta, así que si quedan intentos pasa a `RETRYING` con la espera
  exponencial y un evento `JOB_QUEUED` diferido en el outbox; si no quedan, a `FAILED` con un
  `JOB_DEAD_LETTERED` (misma transacción). El `error` es «El worker dejó de responder durante la ejecución».
- La escritura (`finishExpiredAttempt`) exige `RUNNING`, el mismo intento **y que el lease siga vencido**:
  si el worker renovó entre la lectura y la escritura, o dos instancias recuperan a la vez, solo una
  escritura prospera y **no hay reintentos duplicados**. Un worker rezagado que despierta después no puede
  cerrar ni renovar un intento ya relevado (`attempts` distinto).
- Nunca se toca un trabajo cuyo lease está vigente.

| Propiedad | Por defecto | Significado |
|---|---|---|
| `queuelab.worker.lease.duration` | `2m` | Vida del lease; fija cuánto tarda en detectarse una caída |
| `queuelab.worker.recovery.interval` | `30s` | Cada cuánto busca trabajos abandonados |
| `queuelab.worker.recovery.batch-size` | `50` | Máximo recuperado por pasada |
| `queuelab.worker.recovery.enabled` | `true` | `false` apaga el recuperador (los tests lo llaman a mano con `recoverOnce()`) |

`V10` también fija `lease_expires_at = now()` a los `RUNNING` ya existentes, de modo que los
huérfanos anteriores a la migración se recuperan en la primera pasada. Limitación: con `lease.duration`
menor que lo que tarda un latido en llegar (GC largo, red cortada) un trabajo vivo puede recuperarse y
ejecutarse dos veces; por eso la duración por defecto es holgada y los ejecutores deben ser idempotentes.

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

### Reintento manual de trabajos fallidos

`POST /api/v1/jobs/{id}/retry` devuelve a la cola un trabajo en `FAILED` (agotado, fallo permanente o
abandonado) **sin crear otro trabajo**: el `id` es el mismo.

- Solo es elegible `FAILED`. Cualquier otro estado (`QUEUED`, `RUNNING`, `RETRYING`, `COMPLETED`) responde
  **409** (`El trabajo no se puede reintentar`) sin tocar nada; un id inexistente, 404; uno mal formado, 400.
- La operación es **atómica y sin duplicados**: un `UPDATE … WHERE status = 'FAILED' AND attempts = :leído`
  más la fila de historial y el evento `JOB_QUEUED` del outbox se escriben en una sola transacción. Si
  dos peticiones coinciden, exactamente una gana (202) y la otra recibe 409; no se publica un segundo mensaje.
- El trabajo pasa a `QUEUED` con `attempts = 0` (presupuesto completo de `max-attempts`) y `finished_at`
  vacío. `started_at` y el último `error` se conservan hasta que el nuevo intento los sustituya.
- El mensaje llega al worker por el camino normal (outbox → `OutboxDispatcher` → RabbitMQ). El aviso que
  la DLQ guardó del fallo anterior permanece como registro; no se retira.

**Historial** (`job_retries`, migración `V11`, borrado en cascada con el trabajo): cada reintento guarda
cuándo se pidió, cuántos intentos había consumido el ciclo anterior y su último error. Se consulta con
`GET /api/v1/jobs/{id}/retries`:

```json
[{"requestedAt":"2026-10-05T10:00:00Z","attempts":3,"error":"El servicio externo no responde"}]
```

## Almacenamiento de ficheros

Las entradas (p. ej. el CSV subido) y los resultados (p. ej. `<jobId>.stats.json`) no viven en PostgreSQL. `core`
define la frontera `FileStorage` y la API y el worker dependen solo de ella; hoy hay una implementación,
`LocalFileStorage`, que guarda en un directorio local.

| Operación | Qué hace |
|---|---|
| `store(area, nombre, contenido)` | Guarda y devuelve `StoredFile(referencia, tamaño)`. Escribe en un temporal y lo mueve al destino: es todo o nada, un fallo a medias no deja ni el fichero ni el temporal. Reemplaza lo que hubiera (un reintento reescribe su resultado). |
| `open(referencia)` / `size` / `exists` / `delete` | Operan por referencia; `open` y `size` lanzan `StoredFileNotFoundException` si falta. |

- **Zonas**: `INPUT` → `<directorio>/inputs/`, `RESULT` → `<directorio>/results/`. La referencia es
  `inputs/<nombre>` o `results/<nombre>`, opaca para quien la recibe.
- **Base de datos**: `jobs.input_ref` y `jobs.result_ref` (`varchar(255)`, migración V12) guardan solo la referencia;
  `JobRepository.attachInput/attachResult/findInputRef/findResultRef`. Nunca el contenido.
- **Nombres**: los elige el sistema (el id del trabajo), nunca el nombre que manda el cliente. Lista blanca
  `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` sin `..`: no admite separadores, rutas absolutas, espacios ni caracteres de control.
- **No se puede salir del directorio**: además del formato, cada acceso normaliza la ruta, comprueba que sigue
  dentro de `<directorio>/<zona>` y compara la ruta real, así que un enlace simbólico (al fichero o a la zona)
  que apunte fuera se rechaza con `InvalidStorageReferenceException`. Tanto nombres como referencias pasan por esta
  comprobación en todas las operaciones.
- **Errores**: `InvalidStorageReferenceException` (entrada inválida, no se reintenta) y `StorageException` (E/S:
  disco lleno, permisos; transitorio).
- **Configuración**: `queuelab.storage.directory` / `QUEUELAB_STORAGE_DIRECTORY`, por defecto `./data/storage`
  (relativo al directorio de trabajo del proceso y ignorado por git). API y worker **deben apuntar al mismo
  directorio**; si corren en máquinas distintas hará falta otra implementación de `FileStorage` (p. ej. un almacén
  de objetos), que es justo lo que esta interfaz permite. Los tests usan `target/`.
- La política de retención (entradas, resultados, temporales y huérfanos) está en [`docs/csv-workload.md`](../docs/csv-workload.md#política-de-retención-32) y la ejecuta el worker (ver «Limpieza de ficheros»).

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

### CSV de extremo a extremo (`CsvEndToEndTest`) (#33)

Misma infraestructura, con la API arrancada con un límite de subida de 2 MB (`QUEUELAB_CSV_MAX_FILE_SIZE`) y el
worker con la retención de entradas `COMPLETED` acortada a 4 s (`QUEUELAB_CLEANUP_*`) para ejercitar en segundos
lo que en producción tarda horas. Los casos finos (cada formato, cada error de fila) siguen en los tests de
módulo (`CsvUploadTest`, `CsvImportTest`, `ResultDownloadTest`, `StorageCleanerTest`); aquí se prueba que encajan:

| Escenario | Qué se demuestra |
|---|---|
| CSV de muestra | `POST /api/v1/jobs/csv` (multipart real) → `COMPLETED`, `result` "CSV procesado: 3 filas, 3 columnas", `resultFile` con nombre, tipo y `downloadUrl`; la descarga trae `Content-Disposition: attachment`, el tamaño anunciado y las estadísticas esperadas (suma 14.5, media 7.25, 1 vacío). |
| Fila malformada | `FAILED` con "Línea 3: ...", `attempts = 1`, sin `resultFile`, `GET .../result` → `409`, y la entrada se conserva para un reintento manual. |
| Fichero grande | 100 000 filas (~1,7 MB) atraviesan subida, worker y descarga con estadísticas exactas (mín., máx. y suma). |
| Tamaño excesivo | Un byte por encima del límite → `413` con `detail` y ningún trabajo creado. |
| Formato incorrecto | `image/png` → `415`; fichero vacío → `400`; ningún trabajo creado. |
| Limpieza | Pasada la retención, la entrada de un `COMPLETED` desaparece del disco y su `input_ref` queda a `NULL`, pero el resultado sigue descargable. |

### Fallos esperables (`ReliabilityTest`)

Misma infraestructura (procesos reales, Docker, HTTP y SQL), centrada en lo que puede salir mal:

| Escenario | Qué se demuestra |
|---|---|
| Envío repetido | 6 peticiones simultáneas y una posterior con el mismo `Idempotency-Key` devuelven el mismo `id`: una fila en `jobs`, un evento de outbox, `attempts = 1`. |
| Entrega duplicada | Tras `COMPLETED`, el mismo evento se republica 3 veces (`published_at = NULL`): el worker descarta las copias; `status`, `attempts`, `finishedAt` y `result` no cambian. |
| Caída del broker con el worker conectado | RabbitMQ parado: la API acepta 3 trabajos, siguen `QUEUED` con su evento pendiente y el worker sigue vivo. Al volver, se reconecta y cada trabajo se ejecuta **una vez** (`attempts = 1`). |
| Worker caído a mitad de trabajo | Se deja un `RUNNING` con el lease vencido, que es lo que queda tras matar al worker. Un worker nuevo lo recupera: con intentos libres se reintenta y acaba `COMPLETED` con `attempts = 2`; sin intentos acaba `FAILED` y su `JOB_DEAD_LETTERED` se publica. |

Limitación: el crash del worker se reproduce con el estado que deja en la base de datos, no con un `kill -9`
a mitad de ejecución, porque el único ejecutor (`noop`) es instantáneo. Los hilos y el latido del lease
se prueban aparte en `LeaseRecoveryTest`; los reintentos manuales, en `ManualRetryTest`.
