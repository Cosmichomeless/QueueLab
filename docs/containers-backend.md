# Contenedores del backend

Imágenes Docker de la API (`backend/api/Dockerfile`, #52) y del worker (`backend/worker/Dockerfile`, #53).
Este documento describe cómo se construyen, cómo se configuran y qué se verificó de verdad.

## API (#52)

### Construir

El contexto de build es `backend/` (la API depende del módulo `core`), no `backend/api/`:

```bash
docker build -f backend/api/Dockerfile -t queuelab-api backend
```

`backend/.dockerignore` deja fuera `target/`, `data/`, logs, ficheros de IDE y `.env*`, y los propios Dockerfiles.

### Qué contiene la imagen

| Decisión | Motivo |
|---|---|
| Build multietapa: `eclipse-temurin:25-jdk` compila, `eclipse-temurin:25-jre-alpine` ejecuta | La imagen final no lleva Maven, JDK ni código fuente |
| Jar en capas (`-Djarmode=tools extract --layers`) | Las dependencias (lo que menos cambia) quedan en capas que se reutilizan entre builds |
| Caché de Maven con `RUN --mount=type=cache` | Un cambio en el código no vuelve a descargar dependencias |
| Usuario `queuelab` (uid 10001), sin privilegios | No se ejecuta como root |
| `ENTRYPOINT` en forma exec | `java` es PID 1 y recibe `SIGTERM` directamente |
| `application-local.yml` se borra en la etapa de build | Contiene las credenciales de desarrollo de `docker-compose.yml`: no deben viajar en la imagen |
| `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | La JVM respeta el límite de memoria del contenedor y muere (y se reinicia) ante un OOM en lugar de quedar a medias |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s` | Margen para terminar peticiones en curso al parar |

Consecuencia del borrado del perfil `local`: **desde la imagen no se puede usar `SPRING_PROFILES_ACTIVE=local`**.
Toda la configuración llega por variables de entorno.

### Configuración (todo externo)

La imagen no trae credenciales. Variables relevantes:

| Variable | Para qué | Por defecto |
|---|---|---|
| `QUEUELAB_DB_URL`, `QUEUELAB_DB_USER`, `QUEUELAB_DB_PASSWORD` | PostgreSQL | `jdbc:postgresql://localhost:5434/...`, contraseña vacía |
| `QUEUELAB_RABBITMQ_HOST`, `_PORT`, `_USER`, `_PASSWORD` | RabbitMQ | `localhost`, contraseña vacía |
| `QUEUELAB_REDIS_HOST`, `_PORT`, `_PASSWORD` | Redis (rate limiting) | `localhost` |
| `QUEUELAB_STORAGE_DIRECTORY` | Dónde se guardan entradas y resultados | `/data/storage` (fijado por la imagen) |
| `QUEUELAB_CORS_ALLOWED_ORIGINS`, `QUEUELAB_RATE_LIMIT_*`, `QUEUELAB_BACKPRESSURE_*`, `QUEUELAB_LOG_FORMAT` | Comportamiento de la API | ver `api/src/main/resources/application.yml` |

### Almacenamiento compartido

La API guarda los ficheros subidos y sirve la descarga de resultados; el worker escribe los resultados.
**API y worker deben montar el mismo volumen** en `/data/storage`. Si no, el worker no encuentra la entrada
o la API no encuentra el resultado.

### Migraciones y orden de arranque

La API ejecuta Flyway al arrancar (V1–V14). El worker **asume que el esquema ya existe**, así que la API
debe arrancar primero (en Compose: `depends_on` con `condition: service_healthy` sobre la API).

### Healthcheck

`HEALTHCHECK` consulta `GET /actuator/health` (incluye la base de datos). RabbitMQ y Redis no cuentan:
la API acepta trabajos aunque estén caídos (el outbox los reenvía después). `--start-period=45s`.

### Ejemplo de ejecución

```bash
docker run -d --name queuelab-api -p 8080:8080 \
  -e QUEUELAB_DB_URL=jdbc:postgresql://postgres:5432/queuelab \
  -e QUEUELAB_DB_USER=queuelab -e QUEUELAB_DB_PASSWORD=... \
  -e QUEUELAB_RABBITMQ_HOST=rabbitmq -e QUEUELAB_RABBITMQ_USER=queuelab -e QUEUELAB_RABBITMQ_PASSWORD=... \
  -e QUEUELAB_REDIS_HOST=redis \
  -v queuelab-storage:/data/storage \
  queuelab-api
```

### Verificado (entorno aislado: red, Postgres, RabbitMQ y Redis propios; puerto 127.0.0.1:18080)

- **Build real** de la imagen desde `backend/` con la caché de Maven.
- **Arranque sano**: Flyway aplica las 14 migraciones, la API arranca en ~2 s, `/actuator/health` responde `UP`
  y `POST /api/v1/jobs` devuelve `201`.
- **Healthcheck de verdad**: con Postgres parado, `/actuator/health` devuelve `503` y el contenedor pasa a
  `(unhealthy)`; al reiniciar Postgres vuelve a `(healthy)`.
- **Sin secretos**: no hay `application-local.yml` dentro del jar y `docker run --entrypoint env` no muestra credenciales.
- **Usuario**: el proceso corre como `queuelab`, no como root.
- **Parada ordenada**: `docker stop` envía `SIGTERM`; el log muestra
  `Commencing graceful shutdown` → `Graceful shutdown complete` → cierre de Hikari. Código de salida 143
  (128 + SIGTERM, el habitual de la JVM).
- **Sin configuración**: arranca con los valores por defecto y falla rápido (exit 1) con un error claro
  (`Connection to localhost:5434 refused`), sin quedarse colgado.

### Limitaciones

- No se ha probado en un orquestador (Kubernetes, ECS); solo `docker build`/`docker run`.
- Imagen solo para la arquitectura de la máquina de build (no hay build multiarquitectura).
- No se escanea la imagen en busca de vulnerabilidades.
- `/actuator/health` no refleja RabbitMQ ni Redis (decisión previa del proyecto).

## Worker (#53)

### Construir

Imagen independiente de la API (no comparte capas de aplicación con ella), mismo contexto `backend/`:

```bash
docker build -f backend/worker/Dockerfile -t queuelab-worker backend
```

Sigue las mismas decisiones que la API (multietapa, jar en capas, JRE alpine, uid 10001, `ENTRYPOINT` exec,
perfil `local` eliminado, `JAVA_TOOL_OPTIONS`). El uid/gid coincide a propósito con el de la API: ambos escriben
y leen en el volumen compartido.

El jar solo contiene `application.yml` (verificado con `unzip -l`): sin `application-local.yml` ni credenciales.

### Configuración

`QUEUELAB_DB_*`, `QUEUELAB_RABBITMQ_*` y `QUEUELAB_STORAGE_DIRECTORY` como en la API. No necesita Redis.
Ajustes propios del worker:

| Variable | Para qué | Por defecto |
|---|---|---|
| `QUEUELAB_WORKER_CONCURRENCY` | Trabajos simultáneos **por réplica** (consumidores fijos) | `2` |
| `QUEUELAB_WORKER_LEASE_DURATION` | Cuánto tarda en darse por caído un worker que no late | `2m` |
| `QUEUELAB_METRICS_*` | Endpoint `/metrics` del worker (observabilidad, #46–#48) | solo `127.0.0.1:8081` dentro del contenedor |

Sin `HEALTHCHECK`: el worker no abre puerto HTTP (`web-application-type: none`, sin actuator). La vigilancia
de un worker se hace con el orquestador (reinicio al salir) y con las métricas.

### Escalar réplicas

Las réplicas no se coordinan entre sí: todas consumen la misma cola de RabbitMQ y el reclamo de cada trabajo
es atómico en PostgreSQL. Basta con arrancar más contenedores con la misma configuración y el **mismo volumen**:

```bash
docker run -d --name queuelab-worker-1 --network queuelab \
  -e QUEUELAB_DB_URL=... -e QUEUELAB_DB_USER=... -e QUEUELAB_DB_PASSWORD=... \
  -e QUEUELAB_RABBITMQ_HOST=rabbitmq -e QUEUELAB_RABBITMQ_USER=... -e QUEUELAB_RABBITMQ_PASSWORD=... \
  -v queuelab-storage:/data/storage \
  queuelab-worker
# ... y lo mismo con otro nombre para la segunda réplica
# (con Compose se escalaría con `--scale worker=N`, sin `container_name` fijo; no probado aquí)
```

Capacidad total = `QUEUELAB_WORKER_CONCURRENCY` × réplicas (ver `docs/performance/capacity.md`).

### Parada controlada

`SIGTERM` (`docker stop`) llega a la JVM como PID 1. Comportamiento observado:

1. Spring AMQP deja de tomar mensajes y espera a los trabajos en curso (`Waiting for workers to finish`).
2. Esa espera la fija el `shutdownTimeout` del contenedor de Spring AMQP, que por defecto es **5 s**
   (no el `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s`, que es solo el máximo de la fase).
3. Si un trabajo no ha terminado, se cierra el canal (`Workers not finished` / `Closing channel for unresponsive
   consumer`), se cierra el pool de PostgreSQL y el proceso sale con código 143. No queda colgado ni hace falta `SIGKILL`.
4. Un trabajo interrumpido queda `RUNNING` sin latido. Cuando vence su lease, **cualquier otra réplica** lo recupera
   (`AbandonedJobRecoverer`): cuenta como intento perdido y se reintenta con espera (`RETRYING` → otro worker).

El plazo de gracia por defecto de Docker (10 s) alcanza para este flujo: el cierre real se midió en ~9 s con dos
trabajos largos en curso. Si se sube el `shutdownTimeout` de Spring AMQP, hay que subir también el plazo de gracia
del contenedor (`docker stop -t 40`, o `stop_grace_period` en Compose); si no, Docker mandaría `SIGKILL` antes de
tiempo.

### Verificado (entorno aislado: mismo Postgres, RabbitMQ y volumen que la API de arriba)

- **Build real** de la imagen del worker.
- **Independencia de la API**: con la API en marcha y sin ningún worker, un trabajo `noop` se quedó `QUEUED`
  (6 min en la cola); al arrancar el primer worker se procesó al instante.
- **Escalado a 2 réplicas** compartiendo el volumen: 20 CSV pequeños + 8 CSV de 8,5 MB (≈260 000 filas) completados
  sin errores y con `attempts: 1`. En la ráfaga de CSV grandes el reparto fue 3 trabajos para una réplica y 5 para la
  otra, con los consumidores de ambas réplicas ejecutando a la vez. Con trabajos triviales de 3 filas (~2 ms) el reparto
  es muy desigual (20/2): ningún trabajo se duplicó ni se perdió, pero no hay que esperar equilibrio con trabajos tan cortos.
- **Volumen compartido**: el resultado escrito por un worker se descargó desde la API (`GET /api/v1/jobs/{id}/result` → 200).
- **Parada ordenada con trabajos en curso**: réplica con CPU limitada (`docker update --cpus 0.05`) y lease de 20 s
  ejecutando 2 CSV grandes; `docker stop` → `Waiting for workers to finish` → `Workers not finished` → cierre de canales
  y de Hikari, salida con código 143, sin OOM y en ~9 s.
- **Recuperación tras la parada**: al arrancar otra réplica, `AbandonedJobRecoverer` registró
  `perdió su lease` + `Recuperados 2 trabajo(s) abandonados`, y ambos trabajos terminaron `COMPLETED` con `attempts: 2`.
- **Parada en reposo**: las dos réplicas inactivas terminaron con código 143.
- **Usuario no root** (`uid=10001(queuelab)`) y sin credenciales en el entorno de la imagen.

### Limitaciones

- **Un trabajo que dura más que el `shutdownTimeout` (5 s) no termina durante la parada**: se interrumpe y se rehace
  desde cero tras vencer el lease (no hay reanudación parcial). Para trabajos largos, subir el timeout de Spring AMQP
  (no se ha cambiado aquí: es configuración del worker compartida con otros cambios en curso).
- La recuperación tarda lo que dure el lease (2 min por defecto; 20 s en la prueba) más el intervalo del recuperador.
- Sin `HEALTHCHECK` en la imagen del worker.
- No se ha probado con Docker Compose ni un orquestador: el escalado se probó con `docker run` y varias réplicas.
  `docker-compose.yml` no se modifica en este cambio.
- El límite de CPU de la prueba de parada (`docker update --cpus`) es un artefacto para alargar los trabajos; no es una
  configuración recomendada.
