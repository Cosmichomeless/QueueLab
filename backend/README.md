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
