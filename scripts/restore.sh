#!/usr/bin/env bash
# Restauración de una copia de scripts/backup.sh (#60). DESTRUCTIVA: sustituye la base de datos y el volumen
# `storage-data` del proyecto de Compose por el contenido de la copia.
#
#   scripts/restore.sh backups/queuelab-20260101T000000Z          # pide confirmación
#   scripts/restore.sh backups/queuelab-20260101T000000Z --yes    # sin preguntar
#
# Admite las mismas variables de Compose que backup.sh (COMPOSE_FILE, COMPOSE_ENV_FILES, COMPOSE_PROJECT_NAME).
# Pasos: comprueba las sumas SHA-256, para API y worker, recrea el esquema y carga el volcado, vacía y carga el
# almacenamiento, vuelve a poner en cola los trabajos QUEUED y RETRYING (los mensajes de RabbitMQ pueden no
# corresponder a la copia) y arranca API y worker. No restaura RabbitMQ: ver docs/deployment/data-services-and-backups.md.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -lt 1 ] || [ ! -d "$1" ]; then
  echo "Uso: scripts/restore.sh <directorio-de-la-copia> [--yes]" >&2
  exit 2
fi
backup="${1%/}"
assume_yes="${2:-}"

sha256check() { if command -v sha256sum >/dev/null; then sha256sum -c "$@"; else shasum -a 256 -c "$@"; fi; }

echo "Comprobando la integridad de $backup..."
(cd "$backup" && sha256check SHA256SUMS)

echo
cat "$backup/manifest.txt"
echo
if [ "$assume_yes" != "--yes" ]; then
  printf 'Esto REEMPLAZA la base de datos y los ficheros actuales. ¿Continuar? [s/N] '
  read -r answer
  case "$answer" in s | S | si | sí | y | Y | yes) ;; *) echo "Cancelado."; exit 1 ;; esac
fi

docker compose up -d --wait postgres
docker compose stop api worker >/dev/null

echo "Restaurando PostgreSQL..."
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -q \
  -c "DROP SCHEMA public CASCADE" -c "CREATE SCHEMA public"'
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error' \
  <"$backup/postgres.dump"

echo "Restaurando el volumen de almacenamiento..."
docker compose run --rm --no-deps -T --entrypoint sh api -c \
  'find /data/storage -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar -xf - -C /data/storage' <"$backup/storage.tar"

# El estado de RabbitMQ es independiente de la copia: un trabajo que la base de datos da por publicado puede no estar
# en la cola. Se crea un evento nuevo para cada QUEUED/RETRYING sin evento pendiente; si ya hay un mensaje vivo, el
# segundo lo descarta la reclamación atómica del worker (solo uno gana).
echo "Volviendo a poner en cola los trabajos pendientes..."
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atq' <<'SQL'
INSERT INTO outbox_events (id, job_id, event_type, payload, created_at)
SELECT gen_random_uuid(), j.id, 'JOB_QUEUED', jsonb_build_object('version', 1, 'jobId', j.id), now()
FROM jobs j
WHERE j.status IN ('QUEUED', 'RETRYING')
  AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.job_id = j.id AND e.published_at IS NULL);
SQL

docker compose up -d --wait rabbitmq redis api worker
echo "Restauración terminada."
