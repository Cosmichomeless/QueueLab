# Pruebas e2e del dashboard (Playwright)

Recorrido automatizado, en un navegador real, del flujo principal del dashboard contra la **pila real**
(PostgreSQL, RabbitMQ, API y worker): subir un CSV, verlo en cola, verlo ejecutarse y descargar el resultado.
Los tests viven en `frontend/e2e/` y la configuración en `frontend/playwright.config.ts`.

## Cómo ejecutarlas

Requisitos: Docker en marcha, JDK 25 (`JAVA_HOME` apuntando a él), Node y los puertos `15441`, `15673`, `18091`,
`18092` y `13001` libres.

```bash
cd frontend
npm ci
npm run e2e:install   # una vez: descarga Chromium (y sus dependencias del sistema)
npm run e2e
```

`npm run e2e` no necesita nada levantado de antemano: el `globalSetup` arranca la pila y, al terminar (también si
algo falla), la apaga y borra los contenedores y sus datos.

| Variable | Efecto |
| --- | --- |
| `E2E_SKIP_BUILD=1` | No recompila los jars ni el dashboard (útil al repetir; exige que ya existan). |
| `E2E_DASHBOARD_URL` | Prueba un dashboard que ya está en marcha (p. ej. el contenedor) en lugar de arrancar `next start`. |
| `JAVA_HOME` | JDK con el que se lanzan la API y el worker. |

Tras un fallo, `frontend/test-results/` guarda traza y captura, y `frontend/e2e/.out/` los logs de cada proceso
(`api.log`, `worker.log`, `dashboard.log`). Ambos directorios están en `.gitignore`.

## Cómo está montada la pila

| Pieza | Cómo se levanta | Puerto |
| --- | --- | --- |
| PostgreSQL 18 | `e2e/compose.yml` (proyecto `queuelab-e2e`, `tmpfs`, sin volúmenes) | 15441 |
| RabbitMQ 4 | `e2e/compose.yml` | 15673 |
| API | `java -jar` del jar de `backend/api` | 18091 |
| Worker | `java -jar` del jar de `backend/worker`; métricas en `/metrics` | 18092 |
| Dashboard | `next start`, con `QUEUELAB_API_URL=http://localhost:18091` | 13001 |

Los puertos son deliberadamente distintos de los de desarrollo (y de `docker-compose.yml`) para poder ejecutar las
pruebas con la pila de desarrollo encendida. El almacenamiento de resultados va a un directorio temporal dentro de
`e2e/.out`, y la limitación de tasa de la API está desactivada (`QUEUELAB_RATE_LIMIT_ENABLED=false`) para que el
número de subidas de una ejecución no dependa del límite. La API admite como origen CORS el del dashboard.

El `globalSetup` comprueba antes que los puertos están libres y falla con un mensaje claro si no lo están, en lugar
de probar contra un servicio ajeno.

### Observar el estado «En cola» sin esperas ni carreras

Para ver un trabajo `QUEUED` de forma determinista el test **congela el worker** con `SIGSTOP` antes de subir el
CSV; la API lo deja en cola y RabbitMQ lo retiene. Al reanudarlo con `SIGCONT` el worker lo consume y la página del
trabajo (que sondea) pasa a completado sin recargar. Un `afterEach` reanuda siempre el worker y el teardown envía
`SIGCONT` antes de `SIGTERM`, así que un test que falle a medias no deja un proceso detenido.

Consecuencia: **solo funciona en POSIX** (macOS y Linux). En Windows habría que cambiar `worker-control.ts`.

## Qué cubren

1. **Subida → cola → ejecución → resultado descargable.** Entra por `/`, abre «Nuevo CSV», sube `ventas.csv`, ve
   el estado `QUEUED` («En cola», «Esperando en la cola a que un worker lo recoja.»), reanuda el worker y espera
   `COMPLETED` sin recargar. Comprueba «CSV procesado: 3 filas, 3 columnas», descarga `<id>.stats.json` y valida
   su contenido (3 filas; columnas `id:number`, `city:text`, `price:number`), la fila «Completado» en el listado y
   que el navegador llamó a `POST /api/v1/jobs/csv`, `GET /api/v1/jobs/{id}` y `GET /api/v1/jobs/{id}/result` contra
   la URL de API configurada en tiempo de ejecución.
2. **CSV con una fila rota → trabajo fallido.** Termina en `FAILED`, «El trabajo ha fallado.», región «Fallo» con
   «Línea 3: tiene 2 campos y la cabecera 3» y sin enlace de descarga.
3. **Rechazo de la API.** Una cabecera en blanco muestra «No se pudo enviar el fichero: La primera línea (cabecera)
   está vacía»; tras corregir y reintentar, una cabecera no UTF-8 muestra «…El fichero no es UTF-8 válido». El
   formulario sigue usable, se queda en `/jobs/new` y ninguna respuesta es `201` (no se crea ningún trabajo).
4. **Validación del propio formulario.** `notas.txt` y un `vacio.csv` se rechazan en el navegador, con el botón
   «Enviar» deshabilitado y **sin ninguna llamada `POST` a la API**.

### Hallazgo: qué valida la API y qué el worker

La API solo valida la **primera línea** del CSV (UTF-8 válido y no en blanco). Cualquier otro problema (una fila con
distinto número de campos, bytes inválidos más abajo) se detecta en el worker y aparece como un trabajo `FAILED`, no
como un error de subida. Por eso el caso 3 pone el defecto en la primera línea y el caso 2 es un fallo del worker.

## Probar también el contenedor del dashboard

El recorrido sirve para comprobar la imagen de [`containers-frontend.md`](containers-frontend.md) con un navegador
real, incluida la URL de API en tiempo de ejecución:

```bash
cd frontend
docker build -t queuelab-c-dashboard:e2e .
docker run -d --name queuelab-c-dashboard-e2e -p 13002:3000 \
  -e QUEUELAB_API_URL=http://localhost:18091 queuelab-c-dashboard:e2e
E2E_DASHBOARD_URL=http://localhost:13002 npm run e2e
docker rm -f queuelab-c-dashboard-e2e
```

Con `E2E_DASHBOARD_URL` el `globalSetup` no arranca `next start`, y la API debe aceptar el origen
`http://localhost:13002` por CORS (el `globalSetup` solo permite el del dashboard que él mismo arranca, salvo que
`E2E_DASHBOARD_URL` indique otro, que es el caso).

## Lo que se ha verificado y lo que no

Verificado en macOS, con Docker Desktop y JDK 25:

- 4/4 tests desde un estado limpio (contenedores nuevos, jars empaquetados, dashboard compilado), ~20 s.
- 4/4 contra la imagen Docker del dashboard (`E2E_DASHBOARD_URL`), ~17 s.
- Tras la ejecución no queda ningún contenedor ni proceso de la pila.
- `npm run lint`, `tsc --noEmit` y la suite Vitest (59 tests) siguen en verde con Playwright añadido. Vitest solo
  recoge `*.test.{ts,tsx}`, así que no se mezcla con `e2e/*.spec.ts`.

No verificado:

- Ejecución en Linux/CI real (ver [`ci-frontend.md`](ci-frontend.md) para lo que se hace allí).
- Un entorno completamente virgen (sin `node_modules`, sin Chromium instalado, sin imágenes Docker en caché).
- Windows (por `SIGSTOP`/`SIGCONT`).
- El comportamiento del dashboard ante un `429` (el dashboard no lo gestiona de forma específica; aquí el límite
  de tasa está desactivado).
- Solo se prueba Chromium; no hay proyectos para Firefox ni WebKit.
