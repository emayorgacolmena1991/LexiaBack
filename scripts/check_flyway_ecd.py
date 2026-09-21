"""Consulta versiones Flyway recientes y enlace reglas ECD (no imprime secretos)."""
from __future__ import annotations

import os
from pathlib import Path

import psycopg

backend = Path(__file__).resolve().parents[1]
env_path = backend / ".env"
if env_path.exists():
    for line in env_path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())

conn = psycopg.connect(
    host=os.environ["LEXIA_DB_HOST"],
    port=int(os.environ.get("LEXIA_DB_PORT", "5432")),
    user=os.environ.get("LEXIA_FLYWAY_USER", "lexia_migrator"),
    password=os.environ.get("LEXIA_FLYWAY_PASSWORD"),
    dbname=os.environ.get("LEXIA_DB_NAME", "lexia"),
    connect_timeout=20,
)
try:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT version, description, success
            FROM public.flyway_schema_history
            WHERE version IN ('37', '38', '39')
            ORDER BY installed_rank
            """
        )
        print("flyway_37_39:", cur.fetchall())
        cur.execute("SELECT MAX(version::int) FROM public.flyway_schema_history WHERE version ~ '^[0-9]+$'")
        print("max_version:", cur.fetchone()[0])
        cur.execute("SELECT COUNT(1) FROM app.rule_def WHERE code LIKE 'ECD.%'")
        print("ecd_rules:", cur.fetchone()[0])
        cur.execute(
            """
            SELECT COUNT(1)
            FROM app.validation_def v
            JOIN app.process_definition pd ON pd.id = v.process_definition_id
            WHERE pd.case_type = 'ECD' AND v.rule_def_id IS NOT NULL
            """
        )
        print("ecd_validations_linked:", cur.fetchone()[0])
        cur.execute(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'app'
              AND table_name = 'validation_def'
              AND column_name = 'active'
            """
        )
        print("validation_def.active:", cur.fetchall())
finally:
    conn.close()
