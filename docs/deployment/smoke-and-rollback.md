# Smoke checks y vuelta atrás (#62)

Cómo comprobar que un despliegue funciona de verdad y cómo volver a la versión anterior **sin perder trabajos
pendientes**. Como en [`services-and-costs.md`](services-and-costs.md), **no hay un entorno alojado**: todo se ensayó en
local con el Compose público (`docker-compose.yml` + `docker-compose.prod.yml`), en un proyecto aparte
(`queuelab-rel-test`) con volúmenes propios, borrados al terminar.

## Smoke test: `scripts/smoke.sh`

Envía un CSV conocido (3 filas, `id` numérica con suma 6 y `ciudad` de texto), espera a que el trabajo termine y comprueba
el resultado y las estadísticas descargadas. Termina con código 0 solo si todo coincide.

```bash
scripts/smoke.sh                                   # Compose de desarrollo: http://127.0.0.1:8080

# configuración pública (Caddy, HTTPS, acceso Basic)
SMOKE_URL=https://localhost:8443 SMOKE_USER=usuario SMOKE_PASSWORD=contraseña SMOKE_INSECURE=1 \
  SMOKE_DASHBOARD_URL=https://localhost:8443 scripts/smoke.sh
```

| Variable | Significado |
| --- | --- |
| `SMOKE_URL` | Origen de la API (por defecto `http://127.0.0.1:8080`) |
| `SMOKE_USER`, `SMOKE_PASSWORD` | Credenciales Basic del proxy; van por un fichero temporal, no por la línea de comandos |
| `SMOKE_INSECURE=1` | Acepta la CA interna de Caddy (`curl -k`). Solo para pruebas en local |
| `SMOKE_DASHBOARD_URL` | Si se indica, comprueba también `GET /jobs` del dashboard |
| `SMOKE_TIMEOUT` | Segundos de espera a que el trabajo termine (por defecto 60) |

Pasos: `GET /api/v1/jobs` → 200; `POST /api/v1/jobs/csv` → 201; sondeo de `GET /api/v1/jobs/{id}` hasta `COMPLETED`
(`FAILED` o el tiempo agotado fallan); `result` = `CSV procesado: 3 filas, 2 columnas`; descarga de
`/api/v1/jobs/{id}/result` y comprobación de `rows`, de la suma y el rango de `id` y del tipo de `ciudad`. Necesita
`curl` y `jq`.

Cada ejecución **deja un trabajo `COMPLETED` más**: sus ficheros caducan con la retención normal, pero la fila de `jobs`
no se purga ([`data-services-and-backups.md`](data-services-and-backups.md)).

### Verificado

| Caso | Resultado |
| --- | --- |
| Stack sano (versión `v1`) | 6 comprobaciones OK, código 0 |
| Contraseña incorrecta | `FALLO: GET /api/v1/jobs devolvió 401`, código 1 |
| API inalcanzable | `FALLO: no se pudo conectar`, código 1 |
| Worker parado (`SMOKE_TIMEOUT=8`) | El trabajo queda `QUEUED`; `FALLO: ... no terminó en 8s ... ¿está el worker en marcha?`, código 1 |
| Versión con una migración defectuosa (ver «Cuando hay migraciones») | `POST /api/v1/jobs/csv` devolvió **500**, código 1, **con los 3 contenedores `healthy`** |

El último caso es el motivo de la prueba: el healthcheck de la API solo mira la base de datos, así que una versión que
no puede encolar trabajos sigue «healthy». El smoke sí lo detecta.

## Procedimiento de actualización

Las imágenes se llaman `queuelab-api`, `queuelab-worker` y `queuelab-dashboard`, y `docker-compose.yml` las etiqueta con
`${QUEUELAB_TAG:-latest}`. Para poder volver, hay que **etiquetar la versión actual antes de construir la nueva**:

```bash
export COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml COMPOSE_ENV_FILES=.env.production

for s in api worker dashboard; do docker tag queuelab-$s:latest queuelab-$s:v1; done   # 1. fijar lo que funciona
scripts/backup.sh                                                                      # 2. copia previa
docker compose up -d --build --wait                                                    # 3. versión nueva (latest)
scripts/smoke.sh                                                                       # 4. ¿funciona?
scripts/rollback.sh v1                                                                 # 5. si no: volver a v1
```

Con la configuración pública, el smoke necesita las variables `SMOKE_*` de arriba. La copia del paso 2 es la que permite
volver si la actualización incluye migraciones (ver más abajo); `rollback.sh` hace además la suya salvo `SKIP_BACKUP=1`.

## Vuelta atrás: `scripts/rollback.sh <etiqueta>`

```bash
scripts/rollback.sh v1                  # copia previa + vuelta a las imágenes con etiqueta v1
SKIP_BACKUP=1 scripts/rollback.sh v1    # sin copia previa
```

1. Comprueba que existen las tres imágenes con esa etiqueta; si no, **no toca nada** y lista las disponibles.
2. Hace `scripts/backup.sh` (para API y worker unos segundos y los vuelve a arrancar).
3. Ejecuta `QUEUELAB_TAG=<etiqueta> docker compose up -d --no-build --wait api worker dashboard`: recrea **solo** esos tres
   contenedores. PostgreSQL, RabbitMQ, Redis, Caddy y los volúmenes no se tocan.
4. Si algún servicio no llega a `healthy`, sale con error y explica qué mirar.

### Por qué no se pierden trabajos pendientes

El estado vive fuera de los contenedores que se recrean: los trabajos y el outbox en PostgreSQL, los mensajes en
RabbitMQ y los ficheros en el volumen `storage-data` ([`data-services-and-backups.md`](data-services-and-backups.md)).
La versión a la que se vuelve los encuentra tal cual. Un trabajo `RUNNING` cuyo worker se paró se recupera por *lease*
(la reclamación atómica evita que se procese dos veces).

**Verificado (versión `v2` → `v1`, sin migraciones nuevas):** con `v2` desplegada y el worker en marcha se subieron 20 CSV
de 1,35 MB (20 × 201) y se ejecutó `rollback.sh v1` inmediatamente. En ese momento había 14 `COMPLETED` y 10 `QUEUED`
(más los que el worker tuviera en curso). Tras la vuelta atrás, `docker compose ps` mostró `queuelab-*:v1` y los
**24 trabajos terminaron `COMPLETED`** (4 anteriores + 20), con la cola y la DLQ en 0, y el smoke pasó. No se pudo
comprobar si había un trabajo `RUNNING` justo en la parada.

### Cuando hay migraciones

Flyway Community no tiene migraciones de vuelta atrás, así que lo que importa es qué hizo la migración nueva. Se
probaron los dos extremos con migraciones de prueba (`V16` y `V17`, **no incluidas en el repositorio**):

| Migración de la versión nueva | Vuelta a la antigua con `rollback.sh` | Qué hacer |
| --- | --- | --- |
| **Aditiva** (`V16`: crea una tabla) | **Funciona.** La API antigua arranca con un `WARN` de Flyway (`Schema "public" has a version (16) that is newer than the latest available migration (15)`) y el smoke pasa | Nada más. La tabla sobra, pero no molesta |
| **Destructiva** (`V17`: quita la columna `outbox_events.trace_context` que la versión antigua usa) | **No sirve.** `rollback.sh` termina bien (los tres `healthy`), pero el smoke da **500**: la antigua tampoco puede encolar con el esquema nuevo | `scripts/restore.sh` de la copia previa (abajo) |

Se pensaba que una API antigua sobre un esquema más nuevo **fallaría** al validar; no es así: Flyway solo avisa. Por eso
`rollback.sh` no puede decidir por sí mismo si la vuelta es segura: **hay que pasar el smoke después**.

**Restaurar la copia previa** (destructivo; se pierde todo lo creado desde esa copia). Verificado con la `V17`
destructiva:

```bash
export QUEUELAB_TAG=v1                     # imprescindible: restore.sh arranca la API con esta etiqueta
scripts/restore.sh backups/queuelab-<UTC> --yes
docker compose up -d --no-build --wait     # restore.sh no arranca Caddy ni el dashboard
scripts/smoke.sh
```

En la prueba, tras restaurar la copia anterior a la actualización quedaron Flyway en 16 (sin `V17`), los 27 trabajos
`COMPLETED` de la copia y el smoke en verde sobre `v2`. En esa prueba no se creó ningún trabajo entre la copia y la
restauración (las subidas fallaron con 500), así que **no se midió cuántos se pierden**: serían los creados después de
la copia.

### Trampa verificada: la etiqueta no es persistente

`rollback.sh` pasa `QUEUELAB_TAG` solo a su propia llamada. Un `docker compose up -d` posterior **sin** esa variable
recrea API, worker y dashboard con `latest`: se comprobó, y volvieron a la versión nueva. Para fijarla:

```bash
echo 'QUEUELAB_TAG=v1' >> .env.production    # o exportarla en la sesión
```

`backup.sh` y `restore.sh` también resuelven la imagen de la API con esa variable.

## No verificado

- **Un entorno alojado**: no existe. Tampoco Linux ni `amd64`; todo se probó en macOS arm64 con Docker Desktop.
- **La duración de la indisponibilidad** durante una vuelta atrás: la API y el worker se paran en la copia y se
  recrean después; no se cronometró y depende del tamaño de los datos (la copia) y de los arranques de las JVM.
- Una vuelta atrás con un trabajo `RUNNING` en el momento exacto de la parada, con una migración lenta o con un volumen
  grande.
- Una vuelta atrás **solo del dashboard** o solo de la API (el script recrea siempre los tres).
- Que las imágenes antiguas sigan existiendo: ningún proceso las conserva. Si se borran (`docker image prune`),
  `rollback.sh` lo detecta y no hace nada; habría que reconstruirlas desde la etiqueta de Git correspondiente.
- El smoke con `SMOKE_URL` de un dominio real y certificado válido, y su uso desde una herramienta de monitorización
  externa (no hay ninguna).
