"""Aplica V1–Vn como lexia_migrator y deja baseline Flyway. No imprime secretos."""

from __future__ import annotations

import os
import sys
from pathlib import Path

try:
    import psycopg
except ImportError:
    sys.stderr.write("Instala psycopg: python -m pip install psycopg[binary]\n")
    raise


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value)


def main() -> int:
    backend = Path(__file__).resolve().parents[5]
    load_env(backend / ".env")
    host = os.environ.get("LEXIA_DB_HOST")
    if not host:
        sys.stderr.write("Falta LEXIA_DB_HOST\n")
        return 2

    migration_dir = backend / "src" / "main" / "resources" / "db" / "migration"
    files = sorted(migration_dir.glob("V*.sql"))
    if not files:
        sys.stderr.write("No hay migraciones\n")
        return 2

    conn = psycopg.connect(
        host=host,
        port=int(os.environ.get("LEXIA_DB_PORT", "5432")),
        user=os.environ.get("LEXIA_FLYWAY_USER", "lexia_migrator"),
        password=os.environ.get("LEXIA_FLYWAY_PASSWORD"),
        dbname=os.environ.get("LEXIA_DB_NAME", "lexia"),
        autocommit=True,
        connect_timeout=20,
    )
    try:
        with conn.cursor() as cur:
            for path in files:
                print(f"aplicando {path.name}")
                cur.execute(path.read_text(encoding="utf-8"))

            last = files[-1].name.split("__", 1)[0].lstrip("V")
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS public.flyway_schema_history (
                    installed_rank INTEGER NOT NULL,
                    version VARCHAR(50),
                    description VARCHAR(200) NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    script VARCHAR(1000) NOT NULL,
                    checksum INTEGER,
                    installed_by VARCHAR(100) NOT NULL,
                    installed_on TIMESTAMP NOT NULL DEFAULT now(),
                    execution_time INTEGER NOT NULL,
                    success BOOLEAN NOT NULL,
                    CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
                )
                """
            )
            cur.execute("SELECT 1 FROM public.flyway_schema_history WHERE version = %s", (last,))
            if cur.fetchone() is None:
                cur.execute(
                    """
                    INSERT INTO public.flyway_schema_history (
                        installed_rank, version, description, type, script,
                        checksum, installed_by, execution_time, success
                    ) VALUES (1, %s, '<< Flyway Baseline >>', 'BASELINE',
                              '<< Flyway Baseline >>', NULL, 'lexia_migrator', 0, TRUE)
                    """,
                    (last,),
                )
            print(f"baseline Flyway en version {last}")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
