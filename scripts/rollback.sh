#!/usr/bin/env bash
# Vuelta a una versión anterior de API, worker y dashboard (#62) SIN tocar los datos: PostgreSQL, RabbitMQ y los
# ficheros siguen donde están, así que los trabajos pendientes (QUEUED, RETRYING y los mensajes de la cola) los sigue
# procesando la versión a la que se vuelve. Solo se recrean los tres contenedores de la aplicación, con otra imagen.
#
#   scripts/rollback.sh <etiqueta>                  # p. ej. scripts/rollback.sh v1
#   SKIP_BACKUP=1 scripts/rollback.sh <etiqueta>    # sin copia previa
#
# Requisito: las imágenes queuelab-api, queuelab-worker y queuelab-dashboard con esa etiqueta existen ya en Docker.
# Las etiquetas se ponen ANTES de desplegar la versión nueva (ver docs/deployment/smoke-and-rollback.md):
#   for s in api worker dashboard; do docker tag queuelab-$s:latest queuelab-$s:v1; done
#
# Admite COMPOSE_FILE, COMPOSE_ENV_FILES y COMPOSE_PROJECT_NAME, igual que backup.sh y restore.sh.
# Si la versión antigua no es compatible con el esquema (migraciones posteriores), este script no lo arregla: ver
# «Cuando hay migraciones» en la documentación (la salida es restore.sh de una copia anterior a la actualización).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -ne 1 ] || [ -z "$1" ]; then
  echo "Uso: scripts/rollback.sh <etiqueta-de-imagen>" >&2
  exit 2
fi
tag="$1"
case "$tag" in *[!A-Za-z0-9_.-]* | -* | .*) echo "Etiqueta no válida: $tag" >&2; exit 2 ;; esac

missing=""
for s in api worker dashboard; do
  docker image inspect "queuelab-$s:$tag" >/dev/null 2>&1 || missing="$missing queuelab-$s:$tag"
done
if [ -n "$missing" ]; then
  echo "Faltan imágenes:$missing" >&2
  echo "No se ha tocado nada. Imágenes disponibles:" >&2
  docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -E '^queuelab-(api|worker|dashboard):' | sed 's/^/  /' >&2 || true
  exit 1
fi

echo "Imágenes en marcha ahora:"
docker compose ps --format '  {{.Service}}  {{.Image}}' api worker dashboard || true

if [ "${SKIP_BACKUP:-}" != "1" ]; then
  echo "Copia previa (SKIP_BACKUP=1 para omitirla)..."
  scripts/backup.sh
fi

echo "Volviendo a la etiqueta $tag (los datos no se tocan)..."
if ! QUEUELAB_TAG="$tag" docker compose up -d --no-build --wait api worker dashboard; then
  echo >&2
  echo "FALLO: algún servicio no llegó a «healthy» con la etiqueta $tag." >&2
  echo "Revise 'docker compose logs api' (¿migraciones de Flyway que esa versión no conoce?)." >&2
  echo "Si el esquema es más nuevo que la versión antigua, la vuelta atrás es scripts/restore.sh de una copia" >&2
  echo "anterior a la actualización; ver docs/deployment/smoke-and-rollback.md." >&2
  exit 1
fi

echo "En marcha ahora:"
docker compose ps --format '  {{.Service}}  {{.Image}}  {{.Status}}' api worker dashboard
echo "Vuelta atrás hecha. Compruébelo con scripts/smoke.sh."
echo "AVISO: la etiqueta solo vale para esta ejecución. Un 'docker compose up' posterior sin QUEUELAB_TAG=$tag" >&2
echo "(exportada o en .env.production) recrearía los contenedores con la etiqueta 'latest'." >&2
