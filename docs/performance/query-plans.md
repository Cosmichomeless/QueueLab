# Planes de consulta: listado, recuperación y limpieza

Comparación **antes/después** de la migración `V13__job_file_reference_indexes.sql` sobre una tabla `jobs`
de 1.000.000 de filas. Las consultas son las de `JobRepository` (listado, contrapresión, recuperador de leases)
y las que usa `StorageCleaner` (limpieza de ficheros).

## Escenario

| Elemento | Valor |
| --- | --- |
| Hardware | Apple M4 Pro, 12 núcleos, 24 GB de RAM (portátil, sin otra carga relevante) |
| Base de datos | PostgreSQL 18.6 (`postgres:18-alpine` en Docker Desktop), configuración por defecto |
| Esquema | Migraciones V1–V12 («antes») y V1–V13 («después») |
| Datos | `seed-jobs.sql`: 1.000.000 de trabajos deterministas repartidos en 30 días |

Reparto de estados: 974.252 `COMPLETED`, 25.098 `FAILED`, 500 `QUEUED`, 100 `RETRYING` y 50 `RUNNING`
(30 de ellos con el lease vencido). Cada trabajo terminado conserva su `input_ref` y, si completó,
su `result_ref` (`inputs/N.csv`, `results/N.json`), como ocurre antes de que la limpieza libere los ficheros.

Todas las consultas se ejecutan con `EXPLAIN (ANALYZE, BUFFERS)`. Los tiempos son de **ejecución** (no incluyen
planificación) y de una ejecución con la caché caliente salvo que se indique; sirven para comparar órdenes de
magnitud, no como cifras absolutas.

## Resultados

| # | Consulta (origen) | Antes | Después | Plan después |
| --- | --- | --- | --- | --- |
| Q1 | Listado, primera página (`findPage`) | 0,057 ms | 0,057 ms | Index Scan Backward `jobs_created_at_idx` |
| Q2 | Listado, página profunda con cursor (`findPage`) | 0,071 ms | 0,071 ms | Index Scan Backward `jobs_created_at_idx` |
| Q3 | Listado filtrado por `FAILED` (`findPage`) | 0,059 ms | 0,059 ms | Index Scan Backward `jobs_status_created_at_idx` |
| Q4 | Listado filtrado por `QUEUED` (`findPage`) | 0,044 ms | 0,044 ms | Index Scan Backward `jobs_status_created_at_idx` |
| Q5 | Pendientes acotados (`countWaiting`) | 0,165 ms | 0,165 ms | Index Only Scan `jobs_status_created_at_idx` |
| Q6 | Leases vencidos (`findExpired`) | 0,061 ms | 0,061 ms | Index Scan `jobs_running_lease_idx` |
| Q7 | Entradas liberables (`findReleasableInputs`) | **25,465 ms** | **0,240 ms** | Index Scan `jobs_input_release_idx` |
| Q8 | Resultados liberables (`findReleasableResults`) | **55,329 ms** | **0,041 ms** | Index Scan `jobs_result_release_idx` |
| Q9 | ¿Hay un trabajo que referencie el fichero? (`isReferenced`) | **22,801 ms** | **0,041 ms** | BitmapOr `jobs_input_ref_idx` + `jobs_result_ref_idx` |

### Criterio «paginación y búsqueda de leases usan índices adecuados»

Q1–Q6 **ya usaban índices adecuados** con los índices de V2, V3 y V10, así que no se cambiaron:

- La paginación (Q1–Q4) recorre `jobs_created_at_idx` o `jobs_status_created_at_idx` hacia atrás y se detiene
  al reunir 21 filas, también en una página profunda: al ser por cursor `(created_at, id)`, no hay `OFFSET`
  y el coste no crece con la profundidad (Q2 lee 5 páginas de índice como Q1 lee 4).
- `countWaiting` (Q5) es un Index Only Scan acotado por `LIMIT :cap`, de modo que cuesta como mucho
  `cap` entradas de índice aunque la cola tenga millones.
- La búsqueda de leases (Q6) usa el índice parcial `jobs_running_lease_idx`, que solo contiene trabajos
  `RUNNING` (16 kB con 50 filas), y lee solo las filas vencidas.

### Qué cambió y por qué

Q7–Q9 las ejecuta la limpieza de ficheros (`StorageCleaner`), y **no tenían ningún índice utilizable**:

```
Q7 antes:   Parallel Seq Scan on jobs  (Workers Launched: 2, Rows Removed by Filter: 328192 por worker)
            Buffers: shared hit=6136 read=9564        Execution Time: 25.465 ms
Q8 antes:   Parallel Seq Scan on jobs  Filter: result_ref IS NOT NULL AND status = 'COMPLETED' AND finished_at < ...
            Execution Time: 55.329 ms
Q9 antes:   Parallel Seq Scan on jobs  Filter: input_ref = ... OR result_ref = ...
            Execution Time: 22.801 ms

Q7 después: Index Scan using jobs_input_release_idx  (rows=100)           Execution Time: 0,240 ms
Q8 después: Index Scan using jobs_result_release_idx (rows=100)           Execution Time: 0,041 ms
Q9 después: BitmapOr (jobs_input_ref_idx, jobs_result_ref_idx)            Execution Time: 0,041 ms
```

Antes, Q9 se ejecutaba **por cada fichero huérfano** y Q7/Q8 en cada pasada, así que el coste crecía
linealmente con el tamaño de la tabla (y con trabajos de meses atrás, que ya no necesitan nada). V13 añade
cuatro índices **parciales**:

| Índice | Definición | Sirve a | Tamaño (1 M filas) |
| --- | --- | --- | --- |
| `jobs_input_ref_idx` | `(input_ref) WHERE input_ref IS NOT NULL` | Q9 | 832 kB |
| `jobs_result_ref_idx` | `(result_ref) WHERE result_ref IS NOT NULL` | Q9 | 30 MB |
| `jobs_input_release_idx` | `(finished_at) WHERE input_ref IS NOT NULL AND status IN ('COMPLETED','FAILED')` | Q7 | 456 kB |
| `jobs_result_release_idx` | `(finished_at) WHERE result_ref IS NOT NULL AND status = 'COMPLETED'` | Q8 | 17 MB |

Son parciales porque la limpieza pone la referencia a `NULL` al liberar el fichero: el índice solo contiene
lo que aún está por limpiar. Los tamaños de la tabla de pruebas son un caso desfavorable (todos los
resultados están sin liberar); en producción, con la limpieza al día, son mucho menores. Para comparar,
`jobs_status_created_at_idx` pesa 100 MB, `jobs_pkey` 38 MB y `jobs_created_at_idx` 39 MB.

### Coste y alternativas descartadas

- **Escrituras**: los índices de referencia añaden una entrada por cada fila que fija una referencia
  (`input_ref` al aceptar el CSV, `result_ref` al completar) y otra al liberarla (la actualización a `NULL` los
  saca del índice). Es trabajo adicional por trabajo, no por consulta; no se midió el impacto en el
  rendimiento de inserción de forma aislada (el benchmark de la issue #43 lo recoge en el flujo completo).
- **`jobs_running_lease_idx (lease_expires_at, id)`**: el índice actual solo tiene `lease_expires_at`, por lo que
  `ORDER BY lease_expires_at, id` añade un *Incremental Sort*. Se probó en una caída masiva (20.000 trabajos
  `RUNNING`, con leases vencidos repartidos en 5 minutos): 0,28–0,30 ms con el índice actual frente a
  0,17–0,20 ms con `(lease_expires_at, id)`, porque el `LIMIT 50` hace que el *sort* solo procese unas decenas de
  filas. La mejora no justifica reescribir un índice ya publicado, así que **no se cambia**.

## Cómo reproducirlo

1. Levantar PostgreSQL (`docker compose up -d postgres`) y crear una base aparte:
   `docker compose exec -T postgres psql -U queuelab -d queuelab -c "create database queuelab_bench"`.
2. Aplicar las migraciones **hasta V12** (p. ej. arrancando la API contra esa base con
   `SPRING_FLYWAY_TARGET=12`, o ejecutando los ficheros `V1`…`V12` de
   `backend/core/src/main/resources/db/migration` en orden con `psql -f`).
3. Cargar los datos: `psql -d queuelab_bench -f docs/performance/seed-jobs.sql` (determinista; hace `ANALYZE`).
4. Medir el «antes»: `psql -d queuelab_bench -f docs/performance/explain-queries.sql > before.txt`.
5. Aplicar `V13__job_file_reference_indexes.sql`, ejecutar `ANALYZE jobs` y repetir el paso 4 en `after.txt`.
6. Borrar la base: `drop database queuelab_bench`.

Con `psql` dentro del contenedor se usa `docker compose exec -T postgres psql -U queuelab -d queuelab_bench -f - < fichero`.
Los tiempos variarán con el hardware; lo que debe reproducirse es el **tipo de plan** (Seq Scan → Index/Bitmap Scan).
