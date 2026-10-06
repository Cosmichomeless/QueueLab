# Capacidad y trade-offs

Qué capacidad tiene QueueLab, qué límites la controlan, qué se sacrifica al moverlos y **cómo cambiarlos sin
romper nada**. Las cifras salen del [benchmark](benchmark-results.md) (Apple M4 Pro, 12 núcleos compartidos con
PostgreSQL, RabbitMQ, API y cliente; trabajos `csv-import` de 8,6 MiB). Donde algo es razonamiento de diseño y no
una medida, se indica; los valores absolutos cambian con el hardware, así que **mide en el tuyo** con
[`benchmark.py`](benchmark.py) antes de fiarte de ellos.

## Modelo de capacidad

```
cliente ──▶ API ──▶ [límite de envíos 429] ──▶ [contrapresión 503] ──▶ PostgreSQL (jobs + outbox)
                                                                          │ outbox (lotes)
                                                                          ▼
                                                    RabbitMQ ──▶ worker (concurrency × nº de workers)
```

Un trabajo recorre cuatro etapas y el sistema va a la velocidad de la **más lenta**:

| Etapa | Qué la limita | Valor medido (por defecto) |
| --- | --- | --- |
| Aceptar envíos (API) | Copiar el fichero + 2 inserciones; la cuota por cliente | CSV 8,6 MiB: ≈ 65 /s; `noop`: ≈ 2.000 /s |
| **Outbox → RabbitMQ** | `outbox.batch-size` cada `outbox.dispatch.interval`, confirmación síncrona | **≈ 49 eventos/s** |
| **Ejecución (worker)** | `worker.concurrency` × nº de workers, y el tiempo de cada trabajo | CSV: 8,3 /s con 1 hilo → 40 /s con 8; `noop`: ≈ 2.000 /s |
| Resultado/limpieza | Consultas indexadas (V13) | < 1 ms por pasada |

- **Trabajos largos (≥ 100 ms, como el CSV)**: manda el worker. Capacidad ≈ `workers × concurrency / tiempo por trabajo`,
  hasta que el hardware satura (aquí, ≈ 8 hilos).
- **Trabajos muy cortos (≈ ms)**: manda el outbox (≈ 49/s por defecto), por mucho que se suban los hilos.
- La cola de espera entre etapas está acotada por la contrapresión (`max-pending`); sin ella la espera crece sin límite.

## Límites configurables, valores y trade-offs

| Límite (variable) | Defecto | Subirlo | Bajarlo |
| --- | --- | --- | --- |
| `queuelab.worker.concurrency` (`QUEUELAB_WORKER_CONCURRENCY`) | 2 (1–64) | Más throughput hasta saturar los recursos (**medido**: 1→8 hilos = 4,8×); pasado ese punto, misma capacidad y **doble latencia** (8→16: p50 133→272 ms). Más memoria y conexiones. | Menos carga sobre PostgreSQL y la máquina; menor throughput. |
| `queuelab.worker.prefetch` | 1 | Menos viajes al broker (útil con trabajos de ~ms), pero un worker ocupado **retiene** mensajes que otro libre podría hacer y, si cae, esos mensajes vuelven tarde. | Con 1 el reparto es justo; es lo correcto para trabajos largos. |
| `queuelab.backpressure.max-pending` | 1000 | Absorbe ráfagas más grandes; **la espera máxima en cola crece** proporcionalmente (con 1 worker × 2 hilos y CSV de 8,6 MiB, 1000 pendientes ≈ 68 s de espera; con 8 hilos ≈ 25 s) y también el disco ocupado por entradas pendientes. | Rechaza antes (503): más protección, más reintentos de los clientes. |
| `queuelab.backpressure.retry-after` | 30 s | Menos reintentos prematuros; clientes más lentos en volver. | Reintentos más rápidos; riesgo de «tormenta» de reintentos contra una cola aún llena. |
| `queuelab.rate-limit.max-requests` / `window` | 60 por 1 min y por IP | Clientes legítimos con ráfagas pasan; un solo cliente puede acaparar más cola. | Más equidad entre clientes; más 429 para quien envía en lotes. |
| `queuelab.outbox.batch-size` / `dispatch.interval` | 50 / 1 s | **Medido** (`noop`): lote 200 → 175 eventos/s; lote 200 + 100 ms → 543 eventos/s. Más trabajo por transacción de outbox y más carga de consultas del dispatcher. | Menos carga; techo de ≈ 49/s (el valor por defecto). |
| `queuelab.worker.lease.duration` | 2 m | Una caída tarda más en detectarse (`duración` + `recovery.interval`). | Detección más rápida; con CPU saturada, los latidos (cada ⅓ del lease) pueden retrasarse y recuperarse trabajos que seguían vivos. *Razonamiento de diseño, no medido.* |
| `queuelab.worker.retry.*` | 3 intentos, 5 s ×2, máx. 5 m | Más tolerancia a fallos transitorios; un fallo permanente ocupa un hilo más veces. | Falla antes y llega antes a la DLQ. |
| `queuelab.csv.max-file-size` | 10 MiB | Trabajos más largos y más disco (memoria del worker no crece: es streaming). | Menos disco y trabajos más cortos. |

Pistas para elegir (no sustituyen a medir):

- **Concurrencia**: empieza por el número de núcleos que el worker tiene **para él solo** y ajusta con medidas; el
  pool de Hikari (10 por defecto) también acota, aunque el benchmark con 16 hilos no tuvo errores con ese valor.
- **`max-pending`** = `espera tolerable × capacidad real` (fórmula de [Contrapresión](../../backend/README.md#contrapresión-cola-saturada)),
  con la capacidad **medida**, no la teórica.
- **Cuota por cliente × número de clientes activos** no debería superar la capacidad sostenida del sistema; la
  contrapresión es la red de seguridad global, la cuota es equidad entre clientes.

## Cómo cambiar un límite con seguridad

Regla de oro: **un cambio cada vez, con una medida antes y otra después, y marcha atrás preparada**. Todos los
límites son variables de entorno (sin recompilar) y se aplican reiniciando el proceso.

1. **Mide el punto de partida** con el sistema bajo la carga habitual: `GET /api/v1/queue` (`pending`, `saturated`),
   tiempo de espera y de servicio de los trabajos (`started_at − created_at`, `finished_at − started_at`) y CPU/memoria
   de worker y PostgreSQL. Anota el valor actual de la variable para volver a él.
2. **Cambia una sola variable** y en pasos pequeños (p. ej. `concurrency` ×1,5–2, no ×8). El techo es 64 y el worker
   no arranca con un valor fuera de rango: el error es explícito.
3. **Prueba en un entorno con el mismo hardware** con el benchmark antes de producción:
   `python3 docs/performance/benchmark.py --concurrency 4,8 --worker-env QUEUELAB_WORKER_PREFETCH=1` (o
   `--api-env …`). Mira que **ambas** mejoren o se mantengan: throughput *y* latencia de servicio p50.
4. **Despliega de forma gradual**: reinicia un worker, no todos. Reiniciar es seguro: un trabajo en curso no se
   pierde (el lease vence, el recuperador lo reencola y la ejecución es al menos una vez; ver
   [Recuperación](../../backend/README.md#recuperación-de-trabajos-interrumpidos-lease)). Hazlo con poca carga.
5. **Observa durante un ciclo completo**: vigila 503 (`saturated`), 429, trabajos `RETRYING`/`FAILED`, la cola
   dead-letter y la latencia p50. Con efectos retardados (limpieza, reintentos con espera) espera al menos una vuelta.
6. **Marcha atrás** si empeora: restaura el valor anotado y reinicia. No hay migraciones ni estado que deshacer.

### Qué mirar para decidir qué tocar

| Síntoma | Causa probable | Qué probar |
| --- | --- | --- |
| `pending` alto y sostenido, CPU del worker baja | Faltan hilos | Subir `concurrency` o añadir workers |
| `pending` alto, CPU del worker ya alta | Hardware saturado | Añadir workers en otras máquinas; **no** subir hilos |
| Latencia de servicio sube al subir hilos, throughput no | Pasaste el codo (como 8→16) | Volver al valor anterior |
| 503 frecuentes pero la cola se vacía rápido | `max-pending` demasiado bajo para las ráfagas | Subirlo según la fórmula, sin pasar de la espera tolerable |
| 503 con workers saturados | Capacidad insuficiente, no un problema de umbral | Más capacidad; subir el umbral solo alarga la espera |
| 429 a clientes legítimos | Cuota por IP baja (o varios clientes tras la misma NAT/proxy) | Subir la cuota o activar `forward-headers-strategy` tras el proxy |
| Trabajos cortos y throughput plano ≈ 49/s con workers ociosos | Techo del outbox | Subir `outbox.batch-size` y/o bajar `dispatch.interval` |
| Trabajos `RETRYING` tras subir la concurrencia | Contención (BD, disco, CPU) | Bajar la concurrencia; revisar `retry.*` |

## Lo que no se ha medido (y conviene tener presente)

- **Varios workers a la vez** y **varias instancias de API**: las medidas son de un worker y una API. La
  concurrencia total es `concurrency × workers`, pero el comportamiento de PostgreSQL con muchos workers no se ha medido.
- **`prefetch` > 1**, `lease.duration` y `retry.*` no se han evaluado de forma experimental: lo que dice la tabla
  sobre ellos es razonamiento de diseño.
- **Otros tamaños y tipos de trabajo**: los resultados son de un CSV de 8,6 MiB y del trabajo `noop`.
- **Hardware de destino**: todo corrió en una sola máquina con los servicios compartiendo núcleos; con servicios
  dedicados el codo y las cifras absolutas serán otros.
- **Efecto del límite de envíos y la contrapresión sobre el throughput**: se desactivaron durante la medición (miden
  protección, no capacidad); el límite de envíos añade una llamada a Redis por petición, cuyo coste no se ha medido
  de forma aislada.
