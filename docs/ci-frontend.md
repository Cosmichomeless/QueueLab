# CI del dashboard (#57)

Cada pull request que toca el dashboard ejecuta **lint, comprobación de tipos, tests y build** en GitHub Actions. El
workflow es [`.github/workflows/frontend.yml`](../.github/workflows/frontend.yml).

## Qué hace

| Aspecto | Decisión |
| --- | --- |
| **Cuándo** | `pull_request` hacia `main` cuando cambia algo en `frontend/**` o el propio workflow; también a mano (`workflow_dispatch`). |
| **Dónde** | `ubuntu-latest`. |
| **Node** | 22 con `actions/setup-node`, la misma versión que `frontend/Dockerfile`. |
| **Caché** | `cache: npm` de `setup-node` (guarda `~/.npm` con clave derivada de `frontend/package-lock.json`) y `actions/cache` para `frontend/.next/cache`, que acelera el build de Next.js. |
| **Instalación** | `npm ci --no-audit --no-fund`: instala exactamente lo del lockfile y falla si `package.json` y el lockfile no coinciden. |
| **Pasos** | `npm run lint` → `npm run typecheck` → `npm test` (vitest) → `npm run build`, todos desde `frontend/`. |
| **Permisos** | `contents: read` y nada más. |
| **Concurrencia** | Una ejecución nueva de la misma rama cancela la anterior. |
| **Límite de tiempo** | 15 minutos por job. |
| **Telemetría** | `NEXT_TELEMETRY_DISABLED=1`. |

Los pasos van en este orden a propósito: de más barato a más caro, de modo que un error de estilo o de tipos falla en
segundos sin esperar al build.

## Un fallo impide que el check figure como aprobado

Cada paso es un comando que termina con código distinto de cero si algo está mal; en ese caso el job
`Frontend lint, typecheck, test and build` queda en rojo y el PR muestra el check fallido. Comprobado en local
provocando cada fallo (y revirtiéndolo después):

| Fallo provocado | Paso | Código de salida |
| --- | --- | --- |
| `export const n: number = "texto"` | `npm run typecheck` | 2 |
| `var` + `as any` (reglas de ESLint) | `npm run lint` | 1 |
| Un test con `expect(1).toBe(2)` | `npm test` | 1 |

Para que además **no se pueda fusionar** con el check en rojo hay que activarlo una vez en GitHub (es configuración del
repositorio, no se puede hacer desde un fichero):

*Settings → Branches → regla de `main` → «Require status checks to pass before merging» → añadir
`Frontend lint, typecheck, test and build`.*

## Decisiones y límites

- **Filtro por rutas.** Un PR que solo toca `backend/` o `docs/` no lanza este workflow. Contrapartida conocida: si
  el check se marca como obligatorio, GitHub deja esos PR esperando un check que nunca llega. Salidas: quitar el
  bloque `paths:` o crear un workflow «gemelo» con el mismo nombre de job que se salte con éxito para el resto de
  rutas (mismo caso que en [`ci-backend.md`](ci-backend.md)).
- **No se ejecutan los tests e2e de Playwright.** Necesitan la API, el worker, PostgreSQL y RabbitMQ en marcha; esa
  suite es más lenta y frágil y no es el objetivo de este check rápido. Los tests de vitest (unitarios y de
  componentes) sí corren.
- **Un solo job.** Los pasos comparten `node_modules`; partirlos en jobs obligaría a instalar varias veces.
- **Sin matriz de versiones.** El dashboard es Node 22, igual que su `Dockerfile`.
- **`npm audit` desactivado en la instalación** (`--no-audit`): no es un check de este workflow, y hoy hay
  vulnerabilidades altas heredadas que lo pondrían en rojo sin relación con el cambio del PR.
- **Versiones de las acciones.** Se fijan por etiqueta mayor (`checkout@v7`, `setup-node@v7`, `cache@v6`), las
  últimas publicadas al escribir esto. Fijarlas por SHA sería más estricto; no se hizo para que Dependabot/Renovate
  puedan subirlas sin ruido.
- **No se construye la imagen Docker** del dashboard en este workflow; ver [`containers-frontend.md`](containers-frontend.md).

## Cómo reproducirlo en local

Son los mismos comandos que corre el workflow:

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

## Verificado y no verificado

- **Verificado en local:** `npm ci`, `lint`, `typecheck`, `npm test` (59 tests) y `npm run build` terminan con
  código 0 sobre el código actual; los tres fallos provocados de la tabla anterior hacen fallar su paso; el YAML
  se parsea correctamente.
- **No verificado:** ver la sección «Ejecución en GitHub» más abajo si se ha lanzado; el filtro `paths:` y la
  caché de `.next/cache` solo se pueden comprobar en un runner real. No se pasó `actionlint`.
- **Pendiente de configuración manual:** marcar el check como obligatorio en la protección de `main` (ver arriba).
