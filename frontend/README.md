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
| `components/CsvUploadForm.tsx` | Formulario (componente de cliente): validación, envío, errores y navegación al detalle. |
| `components/JobList.tsx` | Lista (componente de cliente): carga, vacío, error, filtro y paginación. |
| `components/StatusBadge.tsx` | Etiqueta de estado con color y texto (no solo color). |
| `lib/api.ts` | Tipos de la API, `ApiError` (mensaje del `problem+json` o de red), `listJobs` y `uploadCsv`. |
| `lib/csv.ts` | Validación de un CSV en el cliente (`validateCsvFile`) y su límite de tamaño. |
| `lib/format.ts` | Etiquetas de estado en español y formato de fechas. |

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
