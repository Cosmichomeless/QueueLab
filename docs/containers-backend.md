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
