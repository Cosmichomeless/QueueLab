#!/usr/bin/env bash
# Smoke test posterior al despliegue (#62): envía un trabajo CSV con contenido conocido, espera a que termine y
# comprueba su resultado y las estadísticas descargadas. Termina con código 0 solo si todo coincide.
#
#   scripts/smoke.sh                                                     # contra http://127.0.0.1:8080 (Compose de desarrollo)
#   SMOKE_URL=https://localhost:8443 SMOKE_USER=demo SMOKE_PASSWORD=... SMOKE_INSECURE=1 \
#     SMOKE_DASHBOARD_URL=https://localhost:8443 scripts/smoke.sh        # configuración pública (Caddy)
#
# Variables (todas opcionales):
#   SMOKE_URL            origen de la API (por defecto http://127.0.0.1:8080)
#   SMOKE_USER / SMOKE_PASSWORD   credenciales Basic, si hay un proxy con acceso (docker-compose.prod.yml)
#   SMOKE_INSECURE=1     acepta el certificado de la CA interna de Caddy (curl -k); solo para pruebas en local
#   SMOKE_DASHBOARD_URL  si se indica, comprueba también que el dashboard responde en <url>/jobs
#   SMOKE_TIMEOUT        segundos que se espera a que el trabajo termine (por defecto 60)
# Ver docs/deployment/smoke-and-rollback.md.
set -uo pipefail

URL="${SMOKE_URL:-http://127.0.0.1:8080}"
TIMEOUT="${SMOKE_TIMEOUT:-60}"
case "$TIMEOUT" in '' | *[!0-9]* | 0) echo "SMOKE_TIMEOUT debe ser un entero mayor que 0" >&2; exit 2 ;; esac

curl_args=(-sS --max-time 20)
[ "${SMOKE_INSECURE:-}" = "1" ] && curl_args+=(-k)
# La contraseña va por un fichero de configuración temporal para no aparecer en la lista de procesos.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if [ -n "${SMOKE_USER:-}" ]; then
  umask 077
  printf 'user = "%s:%s"\n' "$SMOKE_USER" "${SMOKE_PASSWORD:-}" >"$tmp/curlrc"
  curl_args+=(--config "$tmp/curlrc")
fi

fail() { echo "FALLO: $*" >&2; exit 1; }
ok() { echo "OK    $*"; }
http() { curl "${curl_args[@]}" "$@"; }   # imprime el cuerpo; el código HTTP se pide con -w cuando hace falta

# 1. La API responde y las credenciales (si las hay) valen.
code="$(http -o /dev/null -w '%{http_code}' "$URL/api/v1/jobs")" || fail "no se pudo conectar con $URL"
[ "$code" = "200" ] || fail "GET /api/v1/jobs devolvió $code (se esperaba 200)"
ok "API alcanzable: GET /api/v1/jobs → 200"

# 2. Se envía un CSV conocido: 3 filas; `id` numérica (suma 6) y `ciudad` de texto.
printf 'id,ciudad\n1,Madrid\n2,Sevilla\n3,Bilbao\n' >"$tmp/smoke.csv"
resp="$(http -w '\n%{http_code}' -F "file=@$tmp/smoke.csv;type=text/csv" "$URL/api/v1/jobs/csv")" || fail "no se pudo subir el CSV"
code="${resp##*$'\n'}"
body="${resp%$'\n'*}"
[ "$code" = "201" ] || fail "POST /api/v1/jobs/csv devolvió $code (se esperaba 201): $body"
job="$(printf '%s' "$body" | jq -r '.id // empty')"
[ -n "$job" ] || fail "la respuesta no trae el id del trabajo: $body"
ok "trabajo enviado: $job"

# 3. Se espera a que termine.
deadline=$((SECONDS + TIMEOUT))
status=""
while [ "$SECONDS" -lt "$deadline" ]; do
  body="$(http "$URL/api/v1/jobs/$job")" || fail "no se pudo consultar el trabajo $job"
  status="$(printf '%s' "$body" | jq -r '.status // empty')"
  case "$status" in
    COMPLETED) break ;;
    FAILED) fail "el trabajo $job terminó FAILED: $(printf '%s' "$body" | jq -r '.error // .result // empty')" ;;
  esac
  sleep 1
done
[ "$status" = "COMPLETED" ] || fail "el trabajo $job no terminó en ${TIMEOUT}s (último estado: ${status:-desconocido}); ¿está el worker en marcha?"
ok "trabajo COMPLETED"

# 4. El resultado de texto es el esperado.
result="$(printf '%s' "$body" | jq -r '.result // empty')"
[ "$result" = "CSV procesado: 3 filas, 2 columnas" ] || fail "resultado inesperado: '$result'"
ok "resultado: $result"

# 5. Se descargan las estadísticas y se comprueban.
code="$(http -o "$tmp/stats.json" -w '%{http_code}' "$URL/api/v1/jobs/$job/result")" || fail "no se pudo descargar el resultado"
[ "$code" = "200" ] || fail "GET /api/v1/jobs/$job/result devolvió $code (se esperaba 200)"
jq -e '.rows == 3
  and (.columns[] | select(.name == "id") | .type == "number" and .sum == 6 and .min == 1 and .max == 3)
  and (.columns[] | select(.name == "ciudad") | .type == "text" and .nonEmpty == 3)' "$tmp/stats.json" >/dev/null ||
  fail "las estadísticas no coinciden con el CSV enviado: $(cat "$tmp/stats.json")"
ok "estadísticas correctas (3 filas, id suma 6, ciudad de texto)"

# 6. Opcional: el dashboard responde.
if [ -n "${SMOKE_DASHBOARD_URL:-}" ]; then
  code="$(http -o /dev/null -w '%{http_code}' "${SMOKE_DASHBOARD_URL%/}/jobs")" || fail "no se pudo conectar con el dashboard"
  [ "$code" = "200" ] || fail "GET /jobs del dashboard devolvió $code (se esperaba 200)"
  ok "dashboard: GET /jobs → 200"
fi

echo "Smoke test superado."
