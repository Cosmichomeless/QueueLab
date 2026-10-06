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
| `components/JobList.tsx` | Lista (componente de cliente): carga, vacío, error, filtro y paginación. |
| `components/StatusBadge.tsx` | Etiqueta de estado con color y texto (no solo color). |
| `lib/api.ts` | Tipos de la API, `ApiError` (mensaje del `problem+json` o de red) y `listJobs`. |
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
