#!/usr/bin/env bash
# Replica el job e2e de GitHub Actions en local (requiere Docker).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export LEXIA_DB_HOST=localhost
export LEXIA_DB_PORT=5432
export LEXIA_DB_NAME=lexia
export LEXIA_DB_USER=lexia_app
export LEXIA_DB_PASSWORD=ci_lexia_app
export LEXIA_FLYWAY_USER=lexia_migrator
export LEXIA_FLYWAY_PASSWORD=ci_lexia_migrator
export LEXIA_READONLY_PASSWORD=ci_lexia_readonly
export LEXIA_E2E_ENABLED=true
export PGPASSWORD=ci_postgres

echo "Levantando PostgreSQL 16…"
docker compose -f "$ROOT/backend/docker-compose.ci.yml" up -d postgres
sleep 3

echo "Provisionando roles y base…"
bash "$ROOT/backend/scripts/ci-init-db.sh"

echo "Arrancando backend (perfil ci)…"
cd "$ROOT/backend"
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=ci > "$ROOT/backend-ci.log" 2>&1 &
echo $! > "$ROOT/backend-ci.pid"

for i in $(seq 1 60); do
  if curl -sf http://localhost:8081/actuator/health >/dev/null; then
    echo "Backend listo."
    break
  fi
  sleep 3
done

PGPASSWORD=ci_lexia_migrator psql -h localhost -U lexia_migrator -d lexia \
  -v ON_ERROR_STOP=1 -f "$ROOT/backend/scripts/ci-post-migrate.sql"

echo "Ejecutando Playwright…"
cd "$ROOT/frontend"
export CI=true
export LEXIA_E2E_NO_SERVER=false
export LEXIA_E2E_NO_BACKEND=true
export LEXIA_E2E_BASE_URL=http://localhost:4200
npm run e2e:ci

kill "$(cat "$ROOT/backend-ci.pid")" 2>/dev/null || true
docker compose -f "$ROOT/backend/docker-compose.ci.yml" down

echo "E2E local completado."
