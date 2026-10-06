# Trazas con OpenTelemetry

Una **sola traza** une los tres saltos de un trabajo: la petición HTTP que lo envía, la espera y publicación en el
outbox, y la ejecución en el worker, aunque ocurran en procesos distintos y separados por minutos.

## Cómo viaja el contexto

El contexto usa el formato `traceparent` del W3C y cruza dos fronteras que sobreviven a la espera:

```
cliente ──traceparent──▶ API (span HTTP) ──▶ outbox_events.trace_context ──▶ OutboxDispatcher ──▶ cabecera
                                                  (V15, varchar(55))            outbox.wait/publish   traceparent
                                                                                                     │
                                                          worker: queue.wait + job.process ◀─────────┘
```

1. La API acepta un `traceparent` entrante (si el cliente lo envía, la traza continúa la suya) y abre el span HTTP.
2. Al guardar el evento en el outbox se guarda el `traceparent` del span activo en `outbox_events.trace_context`
   (migración V15). Es el único dato nuevo en base de datos y puede ser `NULL`.
3. El despachador continúa esa traza al publicar y deja en el mensaje la cabecera AMQP `traceparent` (su span de
   publicación) y `x-queuelab-enqueued-at` (ms desde epoch en que el broker confirmó la entrega).
4. El worker continúa desde esa cabecera. Los reintentos y los avisos a la DLQ que el worker guarda en el outbox
   heredan la traza del intento que los originó.

## Spans

| Span | Proceso | Tipo | Qué mide |
|---|---|---|---|
| `POST /api/v1/jobs…` (HTTP) | API | SERVER | La petición (Spring Boot). |
| `outbox.wait` | API | INTERNAL | **Espera en el outbox**: de que se guardó el evento a que el despachador lo recoge. Solo en la primera pasada. |
| `outbox.publish` | API | PRODUCER | Publicación y confirmación del broker; en error si hay nack, timeout o broker caído. |
| `queue.wait` | worker | INTERNAL | **Espera en la cola**: de la entrega al broker a que el worker recibe el mensaje. |
| `job.process` | worker | CONSUMER | **Procesamiento** del mensaje completo (reclamo, ejecución, guardado). |
| `job.execute` | worker | INTERNAL | Solo el ejecutor del trabajo; en error si lanza una excepción. |
| `job.recover` | worker | INTERNAL | Recuperación de un lease vencido: abre una traza propia (no hay petición de la que colgar). |

Atributos útiles: `queuelab.job.id`, `queuelab.job.type`, `queuelab.job.attempt`, `queuelab.outbox.attempt`,
`messaging.destination.name`. Los spans de espera tienen marcas de inicio y fin explícitas: no hay ningún hilo
«viviéndolos», pero se ven como barras del ancho de la espera.

**Privacidad**: los spans solo llevan ids, tipos e intentos; nunca el contenido del fichero ni de las filas.

## Configuración

| Variable | Defecto | Efecto |
|---|---|---|
| `QUEUELAB_TRACING_EXPORT_ENABLED` | `false` | Exporta por OTLP/HTTP. Desactivado, los spans se generan (y propagan) pero no se envían a ningún sitio. |
| `QUEUELAB_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | Colector OTLP/HTTP (Jaeger, Tempo, OpenTelemetry Collector…). |
| `QUEUELAB_TRACING_SAMPLING` | `1.0` | Fracción de trazas que se guardan. El muestreo respeta al padre: una traza se guarda entera o no. |

Solo se exportan trazas; las métricas siguen por Prometheus ([`metrics.md`](metrics.md)) y los logs por la salida
estándar ([logs y correlación](../../backend/README.md#logs-estructurados-e-id-de-correlación)).

### Ver una traza en local

```bash
docker run --rm -p 16686:16686 -p 4318:4318 jaegertracing/jaeger:latest
QUEUELAB_TRACING_EXPORT_ENABLED=true java -jar backend/api/target/queuelab-api-*.jar      # y el worker igual
```

Abre <http://localhost:16686>, servicio `queuelab-api`.

## Degradación

Una traza nunca puede impedir publicar o ejecutar un trabajo:

- Un `trace_context` corrupto en la base de datos o una cabecera `traceparent` inválida se ignoran: el evento se
  publica y el worker abre una traza propia (se pierde el enlace, no el trabajo).
- Mensajes anteriores a esta versión o de otro productor (sin cabeceras) se procesan igual, sin `queue.wait`.
- Si el reloj del worker va por delante del que estampó el mensaje, la espera se acota a cero, nunca negativa.
- Sin colector, el exportador falla en segundo plano sin afectar al flujo.

## Verificación

- `TracingTest` (API): spans reales con PostgreSQL y RabbitMQ; una traza para petición, espera y publicación; la
  cabecera del mensaje apunta al span de publicación; contexto ausente o corrupto.
- `TracingTest` (worker): continuación de la traza, esperas, errores, reintentos, DLQ y recuperación.
- `TracingEndToEndTest` (e2e): **dos procesos reales** (API y worker) exportando por OTLP a un receptor de pruebas;
  el `traceparent` enviado por el cliente aparece en los spans de ambos procesos.

## Limitaciones

- No se instrumentan las consultas JDBC ni las operaciones de Redis: la traza llega hasta el nivel de «trabajo».
- `queue.wait` depende de que los relojes de API y worker estén razonablemente sincronizados (misma máquina o NTP).
- Un mensaje reencolado por RabbitMQ conserva su cabecera original: la segunda entrega mostraría una espera que
  incluye la anterior.
- No hay colector ni dashboard de trazas en `docker-compose.yml`; la visualización queda a elección (Jaeger, Tempo…).
