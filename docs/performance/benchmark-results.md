# Benchmark de throughput y latencia

Mide cuánto tarda el worker en vaciar una cola de trabajos según su **límite de concurrencia**
(`queuelab.worker.concurrency`, variable `QUEUELAB_WORKER_CONCURRENCY`) y qué ritmo sostienen la API y el outbox.
Todo se ejecuta con los procesos reales (jar de la API y del worker, PostgreSQL, RabbitMQ y Redis de
`docker-compose.yml`), con el script reproducible [`benchmark.py`](benchmark.py).

## Escenario

| Elemento | Valor |
| --- | --- |
| Hardware | Apple M4 Pro, 12 núcleos, 24 GB de RAM (portátil, sin otra carga relevante) |
| Software | macOS (Darwin 27), Java 25.0.4.1, Spring Boot 4.1.1; PostgreSQL 18.6, RabbitMQ 4.3.6 y Redis 7.4 en Docker Desktop, configuración por defecto |
| Procesos | 1 API + 1 worker (reiniciado para cada concurrencia) + los tres servicios + el cliente del benchmark: **todo en la misma máquina**, compitiendo por los mismos 12 núcleos |
| Configuración fija | `prefetch=1`, API con `QUEUELAB_RATE_LIMIT_ENABLED=false` y `QUEUELAB_BACKPRESSURE_MAX_PENDING` holgado (se mide el worker, no los límites de entrada), resto por defecto (outbox: lotes de 50 cada 1 s) |
| Escenario `csv-import` | 100 trabajos; cada uno procesa el mismo CSV determinista de **150.000 filas × 6 columnas (8,6 MiB)**, trabajo con CPU y E/S (lectura en streaming y estadísticas por columna) |
| Escenario `noop` (control) | 1.000 trabajos que no hacen nada: aíslan el coste fijo por trabajo (reclamo, transiciones en PostgreSQL, ack) |
| Repeticiones | 1 vuelta de calentamiento (descartada) + 3 medidas por concurrencia; se publica la **mediana** (datos crudos en [`results/`](results/)) |

### Método

1. Con el worker **parado**, la API acepta los N trabajos (8 clientes en paralelo). El outbox los publica en RabbitMQ y
   se espera a que la cola esté llena, de modo que lo medido después no depende del ritmo de envío.
2. Se arranca el worker con la concurrencia a probar y se espera a que todos acaben (`COMPLETED`).
3. Las métricas salen de los timestamps de PostgreSQL: **vaciado** = `max(finished_at) − min(started_at)`;
   **throughput** = N / vaciado; **servicio** = `finished_at − started_at` de cada trabajo (cuánto tarda en
   ejecutarse una vez que un hilo lo coge).

El worker se reinicia para cada medida, así que **cada una incluye el calentamiento de la JVM** de ese proceso
(los primeros trabajos son más lentos): afecta sobre todo al p95 con concurrencias altas, donde los primeros
8–16 trabajos arrancan en frío a la vez. La mediana (p50) es el indicador del régimen estable.

## Resultados: `csv-import` (efecto del límite de concurrencia)

| Concurrencia | Vaciar 100 trabajos | Throughput | Aceleración | Eficiencia | Servicio p50 | Servicio p95 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 12,04 s | 8,3 /s | 1,00× | 100 % | 115 ms | 131 ms |
| 2 | 6,75 s | 14,8 /s | 1,78× | 89 % | 126 ms | 140 ms |
| 4 | 3,84 s | 26,1 /s | 3,14× | 78 % | 131 ms | 205 ms |
| **8** | **2,49 s** | **40,2 /s** | **4,84×** | 60 % | 133 ms | 820 ms |
| 16 | 2,48 s | 40,3 /s | 4,85× | 30 % | **272 ms** | 1.064 ms |

Lectura:

- **El límite de concurrencia manda**: de 1 a 4 el throughput escala casi linealmente (eficiencia ≥ 78 %) y el tiempo
  de servicio apenas cambia (115 → 131 ms), así que cada hilo extra es capacidad real.
- **Satura en torno a 8** en esta máquina: pasar de 8 a 16 **no sube el throughput** (40,2 → 40,3 /s) pero **duplica la
  latencia de cada trabajo** (p50 133 → 272 ms). Los hilos extra solo se reparten la misma CPU/E/S con los demás
  procesos. Más concurrencia que recursos es peor: mismo trabajo total, cada uno tarda más (y con timeouts o leases
  ajustados, más riesgo de reintentos).
- El techo depende de los recursos disponibles, no de QueueLab: aquí los 12 núcleos son compartidos con PostgreSQL,
  RabbitMQ, la API y el cliente. Con workers en máquinas propias el codo se desplaza hacia arriba.
- No hubo ningún trabajo fallido en las 20 ejecuciones.

## Resultados: `noop` (control del coste fijo por trabajo)

| Concurrencia | Vaciar 1.000 trabajos | Throughput | Aceleración | Servicio p50 | Servicio p95 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,74 s | 576 /s | 1,00× | 0,5 ms | 1,4 ms |
| 2 | 0,99 s | 1.005 /s | 1,75× | 0,6 ms | 1,4 ms |
| 4 | 0,67 s | 1.482 /s | 2,57× | 0,7 ms | 1,8 ms |
| 8 | 0,50 s | 1.988 /s | 3,45× | 1,2 ms | 2,6 ms |
| 16 | 0,51 s | 1.942 /s | 3,37× | 2,3 ms | 7,3 ms |

Con trabajos instantáneos el worker procesa **≈ 0,6–2 mil trabajos/s** y satura también en 8: el límite pasa a ser
PostgreSQL (dos transacciones por trabajo) y la contención crece con los hilos (p95 1,4 → 7,3 ms). Es la cota
superior del sistema para trabajos muy cortos.

## Otros cuellos de botella medidos

| Etapa | Medida | Observación |
| --- | --- | --- |
| **Outbox → RabbitMQ** | **≈ 48,7 eventos/s** en cualquier concurrencia (1.000 eventos en 20,5 s) | Es el techo **de extremo a extremo** para trabajos cortos, muy por debajo de lo que el worker puede consumir (≈ 2.000 /s). Lo fija el diseño: `queuelab.outbox.batch-size=50` cada `dispatch.interval=1s` (el dispatcher espera un intervalo entre pasadas) con confirmación síncrona por mensaje. Con trabajos de ≥ 100 ms (como el CSV) no es el límite; con `noop` sí. |
| **Aceptar envíos (API)** | `noop`: ≈ 2.000 /s, p95 5,5 ms · `csv-import` (8,6 MiB): ≈ 65 /s, p50 ≈ 120 ms, p95 ≈ 150 ms | Con 8 clientes en paralelo; el CSV está limitado por copiar el fichero al almacenamiento. Medido con el límite de envíos desactivado: con él activo, la cuota por IP (60/min por defecto) es el límite real de un cliente. |
| **Coste de los índices de V13** | +1,0 µs por inserción, +1,3 µs al reclamar, +3,2 µs al completar, ≈ 0 al liberar (ver abajo) | Despreciable frente a los ≈ 115 ms por trabajo CSV. |

### Coste en escritura de los índices de V13 (deuda de la #41)

[`write-cost.sql`](write-cost.sql) simula el ciclo de vida de 200.000 trabajos CSV sobre dos tablas temporales
(sin y con los 4 índices parciales de V13), alternando el orden y tomando la mediana de 3 rondas:

| Fase (200.000 filas) | Sin V13 | Con V13 | Extra |
| --- | ---: | ---: | ---: |
| 1. Aceptar (`INSERT` con `input_ref`) | 506 ms | 710 ms | +41 % (≈ 1,0 µs/fila) |
| 2. Reclamar (`QUEUED → RUNNING`) | 1.109 ms | 1.378 ms | +24 % (≈ 1,3 µs/fila) |
| 3. Completar (`COMPLETED` + `result_ref`) | 1.377 ms | 2.021 ms | +47 % (≈ 3,2 µs/fila) |
| 4. Liberar ficheros (referencias a `NULL`) | 1.378 ms | 1.389 ms | +1 % |

Cada trabajo paga ≈ 5,5 µs adicionales en total; con trabajos de ≥ 1 ms es < 0,5 % del tiempo de servicio. El
porcentaje es grande solo porque la operación base es muy barata. Se mantiene V13: ahorra 22–55 ms **por pasada de
limpieza** y por fichero huérfano (ver [`query-plans.md`](query-plans.md)).

## Conclusiones

1. **El límite de concurrencia del worker es la palanca de capacidad** para trabajos con CPU/E/S: 1 → 8 hilos da
   4,8× de throughput en esta máquina; a partir de ahí solo empeora la latencia.
2. **El valor óptimo es el número de recursos reales, no «cuanto más mejor»**: el codo (aquí 8 con 12 núcleos
   compartidos) debe medirse en el hardware de destino con este mismo script.
3. **Para trabajos muy cortos manda el outbox (≈ 49 /s por instancia de API)**, no el worker. Si hiciera falta más,
   hay que subir `batch-size` o reducir `dispatch.interval` (el efecto de cambiarlos no se ha medido aún).

## Cómo reproducirlo

```bash
cd backend && ./mvnw -q package -DskipTests && cd ..
docker compose up -d postgres rabbitmq redis
python3 docs/performance/benchmark.py --scenario csv  --jobs 100  --rows 150000 --concurrency 1,2,4,8,16 --repeat 3 --output results/csv-import.json
python3 docs/performance/benchmark.py --scenario noop --jobs 1000 --concurrency 1,2,4,8,16 --repeat 3 --output results/noop.json
docker compose exec -T postgres psql -U queuelab -d queuelab -f - < docs/performance/write-cost.sql
```

El script crea una base temporal (`queuelab_bench43`), un directorio de almacenamiento temporal y la borra al
terminar; deja `queuelab`, tus datos y la cola principal intactos salvo por el vaciado de `queuelab.jobs.queued`
(**no lo ejecutes contra un entorno con trabajos reales en la cola**). Opciones: `--scenario csv|noop|submit`
(`submit` solo mide la aceptación de envíos), `--jobs`, `--rows`, `--concurrency`, `--repeat`, `--submit-clients`.
Las cifras absolutas variarán con el hardware; lo que debe reproducirse es la **forma**: escalado casi lineal,
saturación y empeoramiento de la latencia al superar los recursos.
