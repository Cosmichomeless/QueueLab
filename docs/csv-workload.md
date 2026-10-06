# Trabajo `csv-import`: alcance y límites

Primer trabajo real de QueueLab. Recibe un fichero CSV, valida su cabecera y devuelve el **recuento de filas
y estadísticas simples por columna**. Es deliberadamente pequeño: sirve para ejercitar subida, almacenamiento,
procesamiento en streaming, resultado descargable y reintentos, no para hacer análisis de datos.

Este documento fija el contrato; lo implementan #28 (almacenamiento), #29 (subida), #30 (procesamiento),
#31 (resultado) y #32 (filas erróneas y limpieza).

## Tipos de trabajo

`POST /api/v1/jobs` solo acepta los tipos que el worker sabe ejecutar (`queuelab.jobs.types`):

| Tipo | Estado |
|---|---|
| `noop` | Implementado (trabajo de prueba). |
| `csv-import` | Implementado (#30): ver [Procesamiento](../backend/README.md#procesamiento-de-csv-csv-import). |
| Otros (`image-resize`, conversión de ficheros, análisis por lotes…) | **No implementados**: la API responde 400 con la lista de tipos admitidos. |

Un tipo nuevo se añade a la lista en el mismo cambio que su ejecutor, nunca antes.

## Formato de entrada

| Aspecto | Regla |
|---|---|
| Codificación | UTF-8; se tolera un BOM inicial. Otra codificación o bytes inválidos → fallo permanente. |
| Sintaxis | CSV según RFC 4180: separador `,`, campos entre `"` con `""` como comilla escapada y saltos de línea dentro de comillas; fin de línea `LF` o `CRLF`. |
| Cabecera | **Obligatoria**, en la primera línea. Nombres no vacíos, sin duplicados (sin distinguir mayúsculas), de hasta 100 caracteres. Hasta **100 columnas**. |
| Filas | Cada fila debe tener tantos campos como la cabecera. Un fichero con solo cabecera es válido (0 filas). Las líneas totalmente vacías al final se ignoran; en mitad solo valen si hay una columna (valor vacío). |
| Tipo declarado | `Content-Type: text/csv` (o extensión `.csv`); lo demás se rechaza al subir. |

## Subida

`POST /api/v1/jobs/csv`, `multipart/form-data` con la parte `file` (ver [`backend/README.md`](../backend/README.md#subida-de-csv-post-apiv1jobscsv)).
201 con el trabajo `QUEUED`; 413 si supera el tamaño máximo; 415 si no se declara como CSV; 400 si está vacío, no es UTF-8 o
la primera línea está en blanco. Un rechazo no deja ficheros ni trabajos. En la subida solo se comprueba lo mínimo; la cabecera
completa y las filas se validan al procesar.

## Límites

| Límite | Valor por defecto | Dónde se aplica |
|---|---|---|
| Tamaño máximo del fichero | **10 MiB** (`queuelab.csv.max-file-size`) | En la subida, antes de guardar nada: lo que lo supere se rechaza con 413 y no deja ficheros. |
| Columnas | 100 | Al leer la cabecera (fallo permanente si se supera). |
| Tamaño de un campo | 1 MiB | Al leer (fallo permanente con el número de línea): evita que unas comillas sin cerrar agoten la memoria. |
| Memoria del worker | O(columnas), **no** O(filas) | El fichero se procesa en streaming; nunca se carga entero. |

No hay un límite de filas aparte: lo acota el tamaño del fichero.

## Salida

Dos cosas, ambas deterministas para un mismo fichero:

1. **`result` del trabajo** (texto corto, cabe en `varchar(1000)`):
   `CSV procesado: 1250 filas, 4 columnas`.
2. **Fichero de estadísticas** `<jobId>.stats.json`, guardado en el almacenamiento y descargable (#31):

```json
{
  "rows": 1250,
  "columns": [
    {"name": "id",    "type": "number", "nonEmpty": 1250, "empty": 0,  "min": 1, "max": 1250, "sum": 781875, "mean": 625.5},
    {"name": "city",  "type": "text",   "nonEmpty": 1248, "empty": 2,  "minLength": 3, "maxLength": 22}
  ]
}
```

- `rows` cuenta las filas de datos (sin la cabecera).
- Una columna es `number` si **todos** sus valores no vacíos son numéricos (decimal con `.`); si no, `text`.
  Vacío es la cadena vacía; un espacio no lo es.
- `number` añade `min`, `max`, `sum` y `mean` (media de los no vacíos; sin valores no vacíos, la columna es `text`
  y no lleva más estadísticas que los recuentos).
- `text` añade `minLength` y `maxLength` (en caracteres) de los no vacíos.

### Cómo lo expone la API (#31)

- `GET /api/v1/jobs/{id}` incluye `result` (texto) y, solo si el trabajo está `COMPLETED` y el fichero existe,
  `resultFile` con `name`, `contentType`, `size` y `downloadUrl` (`/api/v1/jobs/{id}/result`).
- Un trabajo pendiente, `RUNNING`, `RETRYING` o `FAILED` **no** anuncia resultado (`resultFile: null`) y su
  descarga responde `409`; un trabajo inexistente o sin fichero responde `404`.

## Fallos

| Situación | Resultado |
|---|---|
| Cabecera ausente, vacía, con nombres vacíos o duplicados, o con más de 100 columnas | `FAILED` con un mensaje claro; **sin reintentos** (reintentar no cambia el fichero). |
| Fila con distinto número de campos, o comillas sin cerrar | `FAILED` indicando el número de línea; sin reintentos: el primer intento es el único (`attempts = 1`) y el trabajo queda `FAILED` (#30, verificado en #32). |
| Fichero no encontrado en el almacenamiento, o error de E/S | Fallo transitorio: se reintenta con espera exponencial como cualquier trabajo. |

El mensaje de error nunca incluye el contenido de las filas, solo la línea y el motivo.

## Ficheros temporales

El CSV subido y el fichero de estadísticas viven en el directorio configurado de `FileStorage`
(`queuelab.storage.directory`, ver [`backend/README.md`](../backend/README.md#almacenamiento-de-ficheros)) bajo
`inputs/` y `results/`; el nombre lo asigna el sistema (`<jobId>`), nunca el cliente.

### Política de retención (#32)

El worker ejecuta una limpieza periódica (`StorageCleaner`) con estas reglas:

| Fichero | Cuándo se borra | Propiedad (por defecto) |
|---|---|---|
| Entrada de un trabajo `COMPLETED` | 1 h después de terminar: ya no hace falta | `queuelab.cleanup.completed-input-retention` (`1h`) |
| Entrada de un trabajo `FAILED` | 7 días después de fallar: se conserva para poder **reintentarlo a mano** | `queuelab.cleanup.failed-input-retention` (`7d`) |
| Resultado (`<jobId>.stats.json`) | 30 días después de terminar | `queuelab.cleanup.result-retention` (`30d`) |
| Temporales `.tmp-*` de escrituras interrumpidas, y ficheros **huérfanos** (ningún trabajo apunta a ellos) | 1 h después de su última modificación (margen para no borrar una subida en curso) | `queuelab.cleanup.temporary-retention` (`1h`) |

- **Nunca** se toca nada de un trabajo `QUEUED`, `RUNNING` o `RETRYING`: la limpieza solo mira estados terminales.
- Tras borrar un fichero se anula su referencia (`jobs.input_ref` / `jobs.result_ref`). Un resultado limpiado deja de
  anunciarse (`resultFile: null`) y su descarga responde `404`; reintentar a mano un fallido cuya entrada ya se
  limpió termina otra vez en `FAILED` con «El trabajo no tiene fichero de entrada» (sin reintentos inútiles).
- La referencia se anula **antes** de borrar el fichero y solo si el trabajo sigue terminado: un reintento manual
  concurrente gana y conserva su entrada. Si el borrado falla, la referencia se restaura y se reintenta en la
  siguiente pasada.
