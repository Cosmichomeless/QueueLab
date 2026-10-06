# Datos, migraciones, copias y colas (#60)

Dónde vive el estado de QueueLab, cómo se migra el esquema, cómo se copia y se restaura, y qué sobrevive a un reinicio
de RabbitMQ. Como en [`services-and-costs.md`](services-and-costs.md), **no hay un entorno alojado**: todo esto se
probó en local con el Compose del repositorio, en un proyecto aparte (`queuelab-data-test`) con volúmenes propios,
borrados al terminar.

## Qué estado hay y dónde

| Estado | Volumen | Es fuente de verdad | Se copia con `backup.sh` |
| --- | --- | --- | --- |
| Trabajos, outbox, reintentos, historial de Flyway | `postgres-data` | **Sí** | Sí (`pg_dump`) |
| Entradas y resultados de los CSV | `storage-data` (API y worker) | **Sí** | Sí (`tar`) |
| Cola y cola de mensajes muertos (DLQ) | `rabbitmq-data` | No: se reconstruye desde PostgreSQL (ver «Restauración») | No |
| Contadores de cuota | Ninguno (Redis sin persistencia) | No | No |
| Métricas y paneles | `prometheus-data`, `grafana-data` | No | No |

## Migraciones (Flyway)

- Las aplica **la API al arrancar** (`V1`–`V15` en `backend/core/src/main/resources/db/migration/`). El worker no
  migra: en Compose espera a que la API esté sana.
- Una migración ya aplicada **no se edita**: Flyway valida las sumas de control y la API se negaría a arrancar. Un
  cambio de esquema es siempre una `V16` nueva.
- Flyway Community no trae migraciones de vuelta atrás. **La vuelta atrás es restaurar una copia** hecha antes de
  actualizar: haga `scripts/backup.sh` antes de desplegar una versión con migraciones nuevas.

Verificado:

- **Desde cero.** Con volúmenes vacíos, la API aplica las 15 migraciones y `flyway_schema_history` queda con 15 filas,
  todas `success`.
- **Actualización con datos.** Se arrancó la API con `SPRING_FLYWAY_TARGET=10`, se insertaron a mano 3 trabajos (un
  `COMPLETED`, un `FAILED`, un `QUEUED`) y un evento de outbox, y se reinició la API sin ese límite: aplicó 5
  migraciones (11 a 15, «now at version v15»), los datos siguieron intactos y la API devolvió el trabajo antiguo.
- Una segunda arrancada sobre el esquema ya migrado responde «Schema "public" is up to date».

No verificado: una migración sobre un volumen grande (la duración y los bloqueos dependen del tamaño; la prueba tuvo
unas pocas filas) y un fallo a mitad de una migración.

## Copias de seguridad

```bash
scripts/backup.sh                                  # guarda en ./backups/queuelab-<UTC>/ (ignorado por Git)
BACKUP_DIR=/ruta/externa BACKUP_KEEP=14 scripts/backup.sh
```

Qué hace, en este orden:

1. Para **API y worker** (solo los que estaban en marcha) para que base de datos y ficheros correspondan al mismo
   instante: un resultado referenciado en PostgreSQL existe en el volumen. Los vuelve a arrancar siempre, también si
   la copia falla. **Es una parada breve de la API y de los workers**; PostgreSQL y RabbitMQ siguen en marcha.
2. `pg_dump --format=custom` de la base de datos → `postgres.dump`.
3. `tar` del volumen `storage-data` (a través del contenedor de la API, sin imágenes extra) → `storage.tar`.
4. `manifest.txt` (versión de Flyway, trabajos por estado, número de ficheros) y `SHA256SUMS`.
5. La copia se escribe como `.partial` y solo se renombra al terminar: una copia a medias nunca cuenta.

Con la configuración pública, indique los mismos ficheros que usó para levantarla (Compose los lee de estas variables):

```bash
COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml COMPOSE_ENV_FILES=.env.production scripts/backup.sh
```

### Retención

| Qué | Política | Dónde se define |
| --- | --- | --- |
| Copias de `backup.sh` | Se conservan las **7 más recientes**; las anteriores se borran al terminar (`BACKUP_KEEP`) | `scripts/backup.sh` |
| Frecuencia recomendada | **Una al día**, y una antes de cada actualización. **No hay planificador**: lo lanza quien opera (p. ej. `cron`, no probado) | Esta página |
| Entradas de trabajos `COMPLETED` / `FAILED` | 1 hora / 7 días | `queuelab.cleanup.*` ([`backend/README.md`](../../backend/README.md)) |
| Resultados descargables | 30 días | `queuelab.cleanup.result-retention` |
| Ficheros temporales y huérfanos | 1 hora | `queuelab.cleanup.temporary-retention` |
| Filas de `jobs` y `outbox_events` | **Sin purga**: crecen sin límite | No hay política en el código |
| Mensajes en la DLQ | **Sin caducidad**: se quedan hasta que alguien los vacía | No hay TTL en la cola |

Con 7 copias diarias se puede volver como mucho 7 días atrás. Una copia más antigua que la retención de ficheros
conserva los trabajos pero **no** los ficheros que el worker ya había borrado; es lo esperado.

**Límite importante:** `./backups` está en el mismo disco que los volúmenes de Docker. Protege de un borrado o de una
migración fallida, **no de perder el disco**. Copiar la carpeta a otro sitio es manual y no está automatizado ni
probado.

## Restauración

```bash
scripts/restore.sh backups/queuelab-20261006T233633Z          # pide confirmación
scripts/restore.sh backups/queuelab-20261006T233633Z --yes
```

**Es destructiva:** sustituye la base de datos y el volumen `storage-data` del proyecto. Comprueba las sumas SHA-256
antes de tocar nada, para API y worker, recrea el esquema `public`, carga el volcado, vacía y carga los ficheros y
arranca todo de nuevo. Debe restaurarse en la misma versión mayor de PostgreSQL que hizo la copia (la imagen fija
`postgres:18`).

**RabbitMQ no se restaura.** Una copia de la base de datos puede decir que un trabajo `QUEUED` ya se publicó
(`published_at` informado) cuando el mensaje ya no está en la cola (volumen nuevo o más reciente). Por eso el script crea
un evento de outbox nuevo para cada trabajo `QUEUED` o `RETRYING` sin evento pendiente. Si el mensaje antiguo sigue vivo,
el segundo lo descarta la reclamación atómica del worker: solo uno gana. Al revés, un mensaje de la cola que apunta a un
trabajo que la copia no tiene va a la **DLQ** (un trabajo inexistente es un fallo permanente): se verá allí.

Verificado (el ciclo completo): con el worker parado se dejó un trabajo en `QUEUED` y se hizo una copia (3 `COMPLETED`
y 1 `QUEUED`, 7 ficheros); se ejecutó `docker compose down -v` (**borra todos los volúmenes, incluido RabbitMQ**), se
creó un proyecto vacío y se restauró. Resultado:

- los 3 trabajos `COMPLETED` y el cuarto figuran en la API; el resultado de uno antiguo se descarga con su contenido;
- el trabajo que estaba `QUEUED`, con RabbitMQ vacío, terminó `COMPLETED` tras la restauración;
- Flyway arranca sobre lo restaurado sin migraciones pendientes (versión 15).

También se comprobó que la retención borra la copia más antigua cuando hay más de `BACKUP_KEEP`, y que si el worker
estaba parado antes de la copia, la copia no lo arranca.

## Colas durables y DLQ

La topología se declara al arrancar y es idempotente ([`JobMessagingTopology`](../../backend/core/src/main/java/com/queuelab/core/messaging/JobMessagingTopology.java)):
exchange `queuelab.jobs` y cola `queuelab.jobs.queued` **durables**, mensajes JSON **persistentes**, y la cola con
dead-letter exchange `queuelab.jobs.dlx` hacia la cola `queuelab.jobs.queued.dlq`, también durable. Los datos de
RabbitMQ van al volumen `rabbitmq-data`.

Verificado con el stack completo de Compose:

| Paso | Cola | DLQ |
| --- | --- | --- |
| Mensaje malformado persistente publicado al exchange con el worker en marcha | 0 | **1** |
| Worker parado y 3 trabajos subidos | **3** (persistentes) | 1 |
| `docker compose restart rabbitmq` (parada ordenada) | **3** | **1** |
| `docker compose kill rabbitmq` (parada brusca) y arranque | **3** | **1** |
| Worker de nuevo en marcha | 0 (los 3 trabajos `COMPLETED`) | 1 |

Es decir: colas, mensajes pendientes y DLQ **sobreviven a un reinicio y a una parada brusca del contenedor** con la
configuración actual. La DLQ no se vacía sola; para inspeccionarla o vaciarla (tras decidir qué hacer con cada mensaje):

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name messages
docker compose exec rabbitmq rabbitmqctl purge_queue queuelab.jobs.queued.dlq   # destructivo
```

No se ha probado: disco lleno, corrupción de disco, un clúster de RabbitMQ (no hay: una sola instancia) ni cuánto
tarda en arrancar con una cola grande. Con RabbitMQ parado, la API sigue aceptando trabajos porque el outbox los
retiene (comportamiento documentado en [`backend/README.md`](../../backend/README.md), **no repetido** en esta
issue).

## Lo que no está resuelto

- Las copias son **manuales**: ni planificador ni copia fuera de la máquina.
- `jobs` y `outbox_events` crecen sin purga; en un uso largo habría que decidir una política.
- Un trabajo `QUEUED` cuyo evento se perdió por otra vía distinta de una restauración (p. ej. editando la base de datos
  a mano) no se vuelve a publicar solo: la migración `V5` solo cubre el momento de migrar. Durante las pruebas, un trabajo
  insertado a mano sin evento se quedó `QUEUED` indefinidamente.
- `backup.sh` y `restore.sh` **no se han probado con `docker-compose.prod.yml`** (se verificarán al desplegar en
  [#61](https://github.com/Cosmichomeless/QueueLab/issues/61)), ni en Linux ni con el perfil `observability`.
- No se probó una copia con tráfico en curso: el script la evita parando API y worker, y por eso no es una copia en
  caliente.
