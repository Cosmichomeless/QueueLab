# CI del backend (#56)

Cada pull request que toca el backend compila y ejecuta **todos los tests de Java** en GitHub Actions. El workflow es
[`.github/workflows/backend.yml`](../.github/workflows/backend.yml).

## Qué hace

| Aspecto | Decisión |
| --- | --- |
| **Cuándo** | `pull_request` hacia `main` cuando cambia algo en `backend/**` o el propio workflow; también a mano (`workflow_dispatch`). |
| **Dónde** | `ubuntu-latest`, que ya trae Docker (lo necesitan los tests con Testcontainers). |
| **Java** | Temurin 25 con `actions/setup-java`, igual que el `Dockerfile` y el `pom.xml` (`java.version`). |
| **Caché** | `cache: maven` de `setup-java`: guarda `~/.m2` con clave derivada de los `pom.xml`. Si no cambian las dependencias, no se descargan otra vez. |
| **Comando** | `./mvnw -B --no-transfer-progress verify`, desde `backend/`. |
| **Permisos** | `contents: read` y nada más. |
| **Concurrencia** | Una ejecución nueva de la misma rama cancela la anterior. |
| **Límite de tiempo** | 30 minutos por job, para que un test colgado no consuma el runner. |
| **Si falla** | Sube como artefacto (7 días) los `surefire-reports` de todos los módulos y `e2e/target/e2e-logs/`. |

## Qué ejecuta `verify`

Recorre los módulos en orden `core → api → worker → e2e`:

1. **Unitarios**, en los tres primeros módulos.
2. **Integración** contra PostgreSQL, RabbitMQ y Redis reales levantados por Testcontainers
   (ver [`integration-tests.md`](integration-tests.md)). No hay servicios que configurar en el workflow.
3. **Extremo a extremo** (`e2e`), que arranca los **jars reales** de la API y el worker: el empaquetado de `api` y
   `worker` ocurre antes, porque el módulo `e2e` va el último del reactor.

El proyecto no usa Failsafe: unitarios e integración corren en la fase `test` de Surefire, así que un único
`verify` cubre ambos tipos. Si el día de mañana se separan, este workflow no necesita cambios mientras se siga
invocando `verify`.

## Un fallo impide que el check figure como aprobado

Maven termina con código distinto de cero si falla cualquier test o la compilación; el paso `Build and test` falla, el
job `Backend build and test` queda en rojo y el PR muestra el check fallido. El paso de subida de logs usa
`if: failure()` y **no** enmascara el resultado.

Para que además **no se pueda fusionar** con el check en rojo hay que activarlo una vez en GitHub (es configuración del
repositorio, no se puede hacer desde un fichero):

*Settings → Branches → regla de `main` → «Require status checks to pass before merging» → añadir
`Backend build and test`.*

## Decisiones y límites

- **Filtro por rutas.** Un PR que solo toca `frontend/` o `docs/` no lanza este workflow (más barato). Contrapartida
  conocida: si `Backend build and test` se marca como obligatorio, GitHub deja esos PR esperando un check que nunca
  llega. Si molesta, hay dos salidas: quitar el bloque `paths:` o crear un workflow «gemelo» con el mismo nombre de job
  que se salte con éxito para el resto de rutas.
- **Un solo job.** El e2e depende de los jars de `api` y `worker`, así que partirlo en jobs obligaría a pasar
  artefactos entre ellos. Un solo job es más simple y, con la caché, razonable en tiempo; si la suite crece, se
  puede dividir por módulo.
- **Sin matriz de versiones.** El proyecto es Java 25 y no se soportan otras.
- **Versiones de las acciones.** Se fijan por etiqueta mayor (`checkout@v7`, `setup-java@v6`, `upload-artifact@v7`),
  las últimas publicadas al escribir esto. Fijarlas por SHA sería más estricto; no se hizo para que Dependabot/Renovate
  puedan subirlas sin ruido.
- **No se construyen las imágenes Docker** (`backend/api/Dockerfile`, `backend/worker/Dockerfile`) en este
  workflow; ver [`containers-backend.md`](containers-backend.md).

## Cómo reproducirlo en local

Es exactamente el mismo comando, con Docker en marcha:

```bash
cd backend
./mvnw -B verify
```

Para un módulo suelto: `./mvnw -B -pl core verify`.

## Verificado y no verificado

- **Verificado:** el YAML se parsea correctamente (Ruby `YAML`); la estructura de disparadores, permisos y job es la
  esperada; `./mvnw -B --no-transfer-progress -pl core verify` termina con `BUILD SUCCESS` en local (101 tests,
  incluidos los de Testcontainers con RabbitMQ); no hay tests que dependan de servicios en `localhost`, así que no
  necesitan `services:` en el runner.
- **No verificado:** la ejecución **en GitHub Actions** (no se puede lanzar desde esta sesión), por lo que no se
  ha visto el check en verde ni el artefacto de logs en un fallo real. Tampoco se pasó `actionlint` (no está
  instalado). Tampoco se ejecutó el `verify` completo de todos los módulos en local.
- **Pendiente de configuración manual:** marcar el check como obligatorio en la protección de `main` (ver arriba).
