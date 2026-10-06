# Tests de integración con PostgreSQL y RabbitMQ reales

Los tests de integración del backend corren contra **PostgreSQL 18 y RabbitMQ 4 reales** levantados por
[Testcontainers](https://testcontainers.com/) y desechados al terminar. No hace falta `docker compose up` ni ningún
servicio instalado a mano: el único requisito es un **Docker en marcha** y Java 25.

Cada clase usa puertos aleatorios del host y una base de datos nueva, así que se pueden ejecutar mientras tienes el
`docker-compose.yml` de desarrollo encendido sin que choquen.

## Qué cubre cada criterio

| Criterio | Dónde se prueba | Qué comprueba |
| --- | --- | --- |
| **Flyway aplica las migraciones** | `api` · `RealServicesStartupTest`, `FlywayMigrationTest`, `OutboxBackfillMigrationTest` | Arranque de la API contra PostgreSQL vacío: se aplican **todas** las migraciones `V*.sql` del classpath (el test las cuenta, así que una migración nueva se incluye sola), ninguna falla ni queda pendiente, existen `jobs` y `outbox_events`, y un segundo `migrate()` no repite nada. |
| **Se declara la topología de RabbitMQ** | `api` · `RealServicesStartupTest`; `core` · `JobMessagingTopologyTest` | Con solo abrir la primera conexión aparecen intercambios, colas (principal y dead-letter) y bindings; un mensaje publicado al intercambio principal llega a `queuelab.jobs.queued` y uno al DLX llega a la DLQ; declarar dos veces es idempotente. |
| **Envío** | `api` · `OutboxDispatcherTest`, `SubmitJobTest`, `IdempotentSubmitTest` | `POST /api/v1/jobs` guarda trabajo + evento de outbox en la misma transacción; el despachador publica con confirmación y solo marca como publicado lo que el broker acepta. |
| **Consumo** | `worker` · `JobConsumerTest`, `AtomicClaimTest`, `ConcurrencyTest`, `RetryTest`, `LeaseRecoveryTest` | El worker consume de la cola real, reclama el trabajo de forma atómica, reintenta y manda a la DLQ. |
| **Resultados** | `worker` · `CsvImportTest`; `api` · `ResultDownloadTest` | El CSV se procesa y el resultado se descarga con sus estadísticas. |
| **Flujo completo, sin atajos** | `e2e` · `EnqueueToCompletionTest`, `CsvEndToEndTest`, `ReliabilityTest` | Los **jars reales** de API y worker corren como procesos separados contra los contenedores; todo se observa por HTTP y SQL: envío → outbox → RabbitMQ → worker → resultado descargable. |

## Cómo ejecutarlos

Todos los comandos, desde `backend/`:

```bash
# Un test concreto (el módulo y sus dependencias se compilan solos)
./mvnw -pl api -am test -Dtest=RealServicesStartupTest -Dsurefire.failIfNoSpecifiedTests=false

# Los de Flyway + RabbitMQ + envío + consumo + resultados por módulo
./mvnw -pl api,worker -am test \
  -Dtest='RealServicesStartupTest,OutboxDispatcherTest,JobConsumerTest,CsvImportTest,JobMessagingTopologyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# El flujo completo con procesos reales: primero empaquetar los jars, luego el módulo e2e
./mvnw -q package -DskipTests
./mvnw -pl e2e test -Dtest='EnqueueToCompletionTest,CsvEndToEndTest'

# Toda la suite (unitarios + integración + e2e)
./mvnw verify
```

El módulo `e2e` va el último en el `pom.xml` porque lanza los jars de `api/target` y `worker/target`; sus logs quedan en
`e2e/target/e2e-logs/`.

## Cómo se montan los contenedores

- **Spring Boot (`api`, `worker`)**: las clases `PostgresTestConfiguration`, `RabbitTestConfiguration`,
  `RedisTestConfiguration` (API) y `ContainersTestConfiguration` (worker) declaran los contenedores como beans con
  `@ServiceConnection`; Spring Boot inyecta URL y credenciales sin tocar `application.yml`. Se activan con
  `@Import(...)` en el test.
- **Sin Spring (`core`, `e2e`)**: el test crea el `PostgreSQLContainer` / `RabbitMQContainer` y pasa sus puertos al
  proceso o al `CachingConnectionFactory`.
- Las imágenes son las mismas que usa el `docker-compose.yml` (`postgres:18-alpine`, `rabbitmq:4-management-alpine`,
  `redis:7-alpine`), así que lo que se prueba es lo que se ejecuta en desarrollo.
- El esquema lo crea Flyway con las migraciones reales de `core`; en `worker` lo aplica un bean `Flyway` de prueba
  porque en producción migra la API.

## Añadir un test nuevo

1. Spring Boot: `@SpringBootTest` + `@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})`
   (copiar de `RealServicesStartupTest`).
2. Limpia el estado en `@BeforeEach` (`DELETE FROM jobs`, `admin.purgeQueue(...)`): el contenedor se comparte entre los
   métodos de la clase.
3. Para esperar efectos asíncronos usa **Awaitility**, nunca `Thread.sleep`.

## Limitaciones

- Necesitan Docker: sin él los tests fallan al arrancar el contenedor, no se saltan.
- La primera ejecución descarga las imágenes; después son rápidas (cada clase tarda unos segundos en arrancar sus
  contenedores).
- Cada clase de test levanta sus propios contenedores; por eso la suite completa tarda más que una de unitarios.
