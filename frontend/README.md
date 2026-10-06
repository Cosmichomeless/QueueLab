# Dashboard de QueueLab

Interfaz web (Next.js 16, App Router, TypeScript) que consulta la API de trabajos. No habla con RabbitMQ ni con
PostgreSQL: solo con la API REST (ver [`backend/README.md`](../backend/README.md), «API de trabajos»).

## Arranque

```bash
cp .env.example .env.local   # NEXT_PUBLIC_API_URL, por defecto http://localhost:8080
npm ci
npm run dev                  # http://localhost:3000
```

Scripts: `npm run lint`, `npm run typecheck`, `npm run build`.

`NEXT_PUBLIC_API_URL` se incluye en el bundle del navegador **al compilar** (`next build`): cambiarla exige
recompilar. Las llamadas salen del navegador, por lo que la API debe permitir el origen del dashboard
(`QUEUELAB_CORS_ALLOWED_ORIGINS`, por defecto `http://localhost:3000`).

## Estructura

| Ruta | Contenido |
|---|---|
| `app/layout.tsx` | Cabecera con la navegación principal y contenedor de página. |
| `app/page.tsx` | `/` redirige a `/jobs`. |
| `app/jobs/page.tsx` | Lista de trabajos. |
| `app/jobs/new/page.tsx` | Formulario de envío de un CSV. |
| `app/jobs/[id]/page.tsx` | Detalle de un trabajo (recibe `params` como `Promise` y delega en `JobDetail`). |
| `components/JobDetail.tsx` | Detalle (componente de cliente): estado, datos, resultado/fallo y actualización automática. |
| `components/CsvUploadForm.tsx` | Formulario (componente de cliente): validación, envío, errores y navegación al detalle. |
| `components/JobList.tsx` | Lista (componente de cliente): carga, vacío, error, filtro y paginación. |
| `components/StatusBadge.tsx` | Etiqueta de estado con color y texto (no solo color). |
| `lib/api.ts` | Tipos de la API, `ApiError` (mensaje del `problem+json` o de red), `listJobs`, `getJob`, `uploadCsv` y `downloadHref`. |
| `lib/jobs.ts` | `isActive(status)` y `POLL_INTERVAL_MS` (2 s), la cadencia del seguimiento. |
| `lib/csv.ts` | Validación de un CSV en el cliente (`validateCsvFile`) y su límite de tamaño. |
| `lib/format.ts` | Etiquetas de estado en español, formato de fechas y de tamaños. |

## Lista de trabajos (`/jobs`)

Tabla con **ID** (los 8 primeros caracteres, enlazan a `/jobs/{id}`), **tipo**, **estado** y **fecha de creación**,
del más reciente al más antiguo. Se pagina con el cursor de la API (botón «Cargar más», sin repetir ni saltar
trabajos) y se puede filtrar por estado. Estados de la pantalla:

| Estado | Qué se ve |
|---|---|
| Cargando | «Cargando trabajos…» (`role="status"`). |
| Vacío | «Todavía no hay trabajos…», o «No hay trabajos en estado «X»» si hay filtro. |
| Error | Alerta con el motivo (el `detail` de la API o «No se pudo conectar con la API») y botón «Reintentar». |
| Error al cargar más | Aviso bajo la tabla; la lista ya cargada se conserva y se puede reintentar. |

Al cambiar de filtro o pulsar «Actualizar» se descartan las respuestas de peticiones anteriores, para que una
respuesta tardía no pise a la vigente.

## Envío de un CSV (`/jobs/new`)

Se llega desde «Nuevo CSV» en la cabecera o desde el botón de la lista. El fichero se valida **antes** de enviarlo
(al elegirlo y al pulsar «Enviar»); si no es válido no se hace ninguna petición y el botón queda deshabilitado.

| Comprobación | Dónde | Mensaje |
|---|---|---|
| Sin fichero | Cliente | «Selecciona un fichero CSV.» |
| Ni extensión `.csv` ni tipo `text/csv` | Cliente | «El fichero debe ser un CSV (extensión .csv o tipo text/csv).» |
| Vacío | Cliente | «El fichero está vacío.» |
| Más de 10 MiB | Cliente | «El fichero pesa X y el máximo es Y.» |
| Cabecera vacía, tamaño real, formato | API (400/413/415) | «No se pudo enviar el fichero: {detail de la API}» |
| API caída o CORS | Red | «No se pudo enviar el fichero: No se pudo conectar con la API…» |

Mientras se envía el botón pone «Enviando…» y queda deshabilitado (no hay doble envío). Si la API acepta el
fichero (201) se navega a `/jobs/{id}` del trabajo creado; si no, se queda en el formulario con el fichero elegido
y el error en un `role="alert"` enlazado al campo (`aria-describedby`).

`MAX_CSV_BYTES` (`lib/csv.ts`) replica el límite por defecto de la API (`queuelab.csv.max-file-size`, 10 MB) solo
para ahorrar la subida; la API sigue siendo quien decide, y si su límite es menor el 413 se muestra igualmente.
Para que el navegador pueda leer esos errores, la API los devuelve también con las cabeceras CORS (el CORS es un
filtro, no solo configuración de MVC, por lo que cubre respuestas como el 413 del multipart).

## Detalle de un trabajo (`/jobs/{id}`)

Es a donde llevan los enlaces de la lista y la redirección tras enviar un CSV. Muestra el estado (etiqueta con color
**y** texto), ID, tipo, intentos y las fechas de creación, inicio y fin, más un bloque según el estado:

| Estado | Qué se ve |
|---|---|
| `QUEUED` | «Esperando en la cola a que un worker lo recoja. Se actualiza solo.» |
| `RUNNING` | «Un worker lo está procesando. Se actualiza solo.» |
| `RETRYING` | «El intento anterior falló; se volverá a ejecutar.» y, si la API lo da, «Último fallo» con el motivo. |
| `COMPLETED` | Bloque «Resultado» con el texto y, si hay `resultFile`, el enlace de descarga (nombre, tipo y tamaño). |
| `FAILED` | Bloque «Fallo» con el `error` del trabajo. |

**Seguimiento sin recargar**: mientras el estado sea `QUEUED`, `RUNNING` o `RETRYING` se vuelve a pedir el trabajo
cada `POLL_INTERVAL_MS` (2 s) con un `setTimeout` encadenado (no se solapan peticiones). Al llegar a `COMPLETED` o
`FAILED` se para y no se hacen más peticiones. Al salir de la página o cambiar de `id` se cancela el temporizador y
se descarta la respuesta en vuelo. El cambio de estado se anuncia en una región `role="status"` (`aria-live`).

Errores:

| Situación | Comportamiento |
|---|---|
| Primera carga falla (API caída, 5xx) | Alerta con el motivo y botón «Reintentar». |
| Trabajo inexistente (404) o identificador no válido (400) | «No existe ningún trabajo con ese identificador.», sin reintento. |
| Falla una actualización con datos ya mostrados | Se conserva lo último conocido, aviso «No se pudo actualizar el estado (…)» y se sigue reintentando; el aviso desaparece al recuperarse. |

La descarga apunta directamente a la API (`API_URL` + `downloadUrl`); es de otro origen, así que el navegador se
apoya en el `Content-Disposition: attachment` de la API.
