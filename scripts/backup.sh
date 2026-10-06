#!/usr/bin/env bash
# Copia de seguridad de QueueLab (#60): PostgreSQL (pg_dump, formato custom) y volumen `storage-data` (tar), tomados
# con la API y el worker PARADOS para que base de datos y ficheros correspondan al mismo instante (un resultado
# referenciado en la base de datos existe en el volumen). Los vuelve a arrancar al terminar, también si algo falla.
#
#   scripts/backup.sh
#
# Variables (todas opcionales):
#   BACKUP_DIR   donde se guardan las copias (por defecto ./backups, ignorado por Git)
#   BACKUP_KEEP  cuántas copias se conservan; las más antiguas se borran al terminar (por defecto 7)
# Con la configuración pública indique los mismos ficheros que usó para levantarla:
#   COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml COMPOSE_ENV_FILES=.env.production scripts/backup.sh
# Para otro proyecto de Compose: COMPOSE_PROJECT_NAME=<nombre>. Ver docs/deployment/data-services-and-backups.md.
set -euo pipefail
cd "$(dirname "$0")/.."

BACKUP_DIR="${BACKUP_DIR:-backups}"
BACKUP_KEEP="${BACKUP_KEEP:-7}"
case "$BACKUP_KEEP" in '' | *[!0-9]* | 0) echo "BACKUP_KEEP debe ser un entero mayor que 0" >&2; exit 2 ;; esac

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$BACKUP_DIR/queuelab-$stamp"
partial="$final.partial"
mkdir -p "$partial"

# Solo se vuelve a arrancar lo que estaba en marcha.
stopped="$(docker compose ps --status running --services | grep -E '^(api|worker)$' | tr '\n' ' ' || true)"
cleanup() {
  status=$?
  if [ -n "$stopped" ]; then
    # shellcheck disable=SC2086
    docker compose start $stopped >/dev/null
  fi
  if [ "$status" -ne 0 ]; then
    rm -rf "$partial"
    echo "La copia falló: no se ha guardado nada en $final" >&2
  fi
}
trap cleanup EXIT

if [ -n "$stopped" ]; then
  echo "Parando $stopped para obtener una copia coherente..."
  # shellcheck disable=SC2086
  docker compose stop $stopped >/dev/null
fi

echo "Copiando PostgreSQL..."
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner' \
  >"$partial/postgres.dump"

echo "Copiando el volumen de almacenamiento..."
docker compose run --rm --no-deps -T --entrypoint tar api -cf - -C /data/storage . >"$partial/storage.tar"

# El manifiesto es solo informativo (qué contiene la copia); la integridad la comprueba SHA256SUMS.
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq' >"$partial/manifest.txt" <<'SQL'
SELECT 'creada_utc ' || to_char(now() AT TIME ZONE 'utc', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
SELECT 'flyway ' || coalesce((SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1), 'ninguna');
SELECT 'trabajos_' || status || ' ' || count(*) FROM jobs GROUP BY status ORDER BY status;
SQL
echo "ficheros_storage $(tar -tf "$partial/storage.tar" | grep -vc '/$' || true)" >>"$partial/manifest.txt"

(cd "$partial" && sha256 postgres.dump storage.tar manifest.txt >SHA256SUMS)
mv "$partial" "$final"
echo "Copia guardada en $final"

# Retención: se conservan las BACKUP_KEEP más recientes. Solo se tocan directorios con el nombre exacto que crea este
# script (los .partial y cualquier otra cosa de BACKUP_DIR se ignoran).
old="$(ls -1d "$BACKUP_DIR"/queuelab-????????T??????Z 2>/dev/null | sort -r | tail -n +"$((BACKUP_KEEP + 1))" || true)"
if [ -n "$old" ]; then
  echo "$old" | while read -r dir; do
    echo "Retención: borrando $dir"
    rm -rf "$dir"
  done
fi
