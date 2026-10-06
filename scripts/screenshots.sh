#!/usr/bin/env bash
# Regenera docs/screenshots/*.png: levanta un stack de Compose limpio (proyecto aparte, `queuelab-shots`, con sus
# propios volúmenes), siembra los datos, captura con Playwright y lo apaga borrando solo esos volúmenes.
# Necesita Docker, Node 22 y `npm ci` hecho en frontend/. La primera vez, `npx playwright install chromium`.
# No debe haber nada en los puertos 3000 y 8080 (pare el stack normal con `docker compose down`).
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT="${COMPOSE_PROJECT_NAME:-queuelab-shots}"
trap 'docker compose -p "$PROJECT" down -v --remove-orphans' EXIT

docker compose -p "$PROJECT" up -d --wait --build
(cd frontend && COMPOSE_PROJECT_NAME="$PROJECT" node scripts/screenshots.mjs)
