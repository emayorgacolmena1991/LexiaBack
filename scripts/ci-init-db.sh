#!/usr/bin/env bash
# Provisiona roles y base lexia para CI (PostgreSQL efímero).
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="${PGPASSWORD:-ci_postgres}"
export PGPASSWORD

MIGRATOR_PWD="${LEXIA_FLYWAY_PASSWORD:-ci_lexia_migrator}"
APP_PWD="${LEXIA_DB_PASSWORD:-ci_lexia_app}"
READONLY_PWD="${LEXIA_READONLY_PASSWORD:-ci_lexia_readonly}"

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'lexia_migrator') THEN
    CREATE ROLE lexia_migrator LOGIN PASSWORD '${MIGRATOR_PWD}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  ELSE
    ALTER ROLE lexia_migrator PASSWORD '${MIGRATOR_PWD}';
  END IF;

  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'lexia_app') THEN
    CREATE ROLE lexia_app LOGIN PASSWORD '${APP_PWD}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  ELSE
    ALTER ROLE lexia_app PASSWORD '${APP_PWD}';
  END IF;

  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'lexia_readonly') THEN
    CREATE ROLE lexia_readonly LOGIN PASSWORD '${READONLY_PWD}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  ELSE
    ALTER ROLE lexia_readonly PASSWORD '${READONLY_PWD}';
  END IF;
END
\$\$;

ALTER ROLE lexia_app INHERIT;
ALTER ROLE lexia_readonly INHERIT;

SQL

if ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'lexia'" | grep -q 1; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 \
    -c "CREATE DATABASE lexia OWNER lexia_migrator ENCODING 'UTF8' TEMPLATE template0"
fi

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 <<SQL
ALTER DATABASE lexia OWNER TO lexia_migrator;
ALTER DATABASE lexia SET timezone TO 'America/Bogota';
REVOKE ALL ON DATABASE lexia FROM PUBLIC;
GRANT CONNECT ON DATABASE lexia TO lexia_migrator, lexia_app, lexia_readonly;
GRANT CREATE ON DATABASE lexia TO lexia_migrator;
SQL

echo "Base lexia provisionada para CI."
