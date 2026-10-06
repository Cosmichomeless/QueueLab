# Dashboard en contenedor

Imagen de producción del dashboard (`frontend/`, Next.js 16 con `output: "standalone"`).

## Construir y ejecutar

```bash
docker build -t queuelab-dashboard frontend
docker run --rm -p 3000:3000 -e QUEUELAB_API_URL=http://localhost:8080 queuelab-dashboard
# → http://localhost:3000  (redirige a /jobs)
```

El contexto de construcción es `frontend/` (el `.dockerignore` deja fuera `node_modules`, `.next` y los `.env*`
locales). La construcción necesita red: `next/font/google` descarga las fuentes Geist al compilar y quedan
incluidas en la imagen; en ejecución el contenedor no sale a Internet.

## Configuración

| Variable | Defecto | Efecto |
| --- | --- | --- |
| `QUEUELAB_API_URL` | `http://localhost:8080` | URL **base de la API tal como la ve el navegador** (el dashboard llama a la API desde el navegador, no desde el contenedor). |
| `PORT` | `3000` | Puerto del servidor dentro del contenedor. |
| `HOSTNAME` | `0.0.0.0` | Interfaz de escucha. |

**La URL de la API se lee al arrancar, no al construir**: la misma imagen sirve para desarrollo, pruebas y
producción cambiando solo la variable. Cómo funciona (`frontend/lib/config.ts`, `frontend/app/layout.tsx`):

1. El layout raíz es dinámico (`await connection()`), de modo que se renderiza en cada petición y lee
   `QUEUELAB_API_URL` del entorno del proceso.
2. Inyecta en `<head>` un `<script>` con `window.__QUEUELAB_API_URL__ = "…"` (con `<` escapado, así que el valor
   no puede cerrar la etiqueta).
3. `lib/api.ts` pide la URL en cada llamada: en el navegador usa ese valor; durante el renderizado en el servidor,
   la variable de entorno. Ambos coinciden, así que no hay desajuste de hidratación.
4. Para desarrollo local (`npm run dev`) sigue valiendo `NEXT_PUBLIC_API_URL` en `.env.local`.

Como el navegador llama directamente a la API:

- la URL debe ser alcanzable **desde el navegador** (no sirve un nombre interno de la red de Docker salvo que el
  navegador también lo resuelva);
- la API debe permitir el origen del dashboard en `QUEUELAB_CORS_ALLOWED_ORIGINS` (por defecto
  `http://localhost:3000`); con otro puerto u origen hay que añadirlo.

## Imagen

- Multietapa (`node:22-alpine`): dependencias (`npm ci`, capa cacheada) → compilación → runtime con solo
  `.next/standalone` y `.next/static` (sin `node_modules` completo ni código fuente). Arg `NODE_VERSION` (22).
- Corre como el usuario no root `node` (uid 1000).
- `HEALTHCHECK` contra `/jobs` (`wget`, ya presente en Alpine): el contenedor pasa a `healthy` al servir el dashboard.
- Tamaño medido: 330 MB (`docker image ls`).
- No hay carpeta `public/` en el proyecto, por eso el `Dockerfile` no la copia; si se añade, hay que copiarla
  a `./public` en la etapa de runtime.

## Verificación realizada

Con `docker build -t queuelab-c-dashboard:test frontend` y dos contenedores a la vez con la **misma imagen**:

| Contenedor | `QUEUELAB_API_URL` | HTML de `/jobs` |
| --- | --- | --- |
| 1 | `https://api-uno.example.com/` | `window.__QUEUELAB_API_URL__="https://api-uno.example.com"` |
| 2 | `http://api-dos:9090` | `window.__QUEUELAB_API_URL__="http://api-dos:9090"` |

Ambos respondieron `/jobs` con el dashboard («Trabajos · QueueLab»), `/` con 307 → `/jobs`, `id` = `uid=1000(node)`
y estado `healthy`. Pruebas unitarias de la resolución de la URL: `frontend/lib/config.test.ts`.

**No verificado todavía:** la llamada real del navegador a la API desde el contenedor (no había un Chrome
disponible en la sesión); lo cubre el recorrido Playwright de [`docs/e2e-dashboard.md`](e2e-dashboard.md) (#50).
Tampoco se ha probado el contenedor junto a la API en un `docker compose` (eso es la #55).
