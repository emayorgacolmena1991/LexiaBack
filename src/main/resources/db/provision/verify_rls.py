"""Comprueba semilla y RLS. No imprime secretos."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import psycopg


def load_env(path: Path) -> None:
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value)


def main() -> int:
    backend = Path(__file__).resolve().parents[5]
    load_env(backend / ".env")
    conn = psycopg.connect(
        host=os.environ["LEXIA_DB_HOST"],
        port=int(os.environ.get("LEXIA_DB_PORT", "5432")),
        user=os.environ["LEXIA_DB_USER"],
        password=os.environ["LEXIA_DB_PASSWORD"],
        dbname="lexia",
        connect_timeout=15,
    )
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT set_config('app.current_tenant_id', %s, false)", ("",))
            cur.execute("SELECT count(*) FROM app.legal_entity")
            empty = cur.fetchone()[0]
            cur.execute(
                "SELECT set_config('app.current_tenant_id', %s, false)",
                ("b1000000-0000-7000-8000-000000000001",),
            )
            cur.execute("SELECT count(*) FROM app.legal_entity")
            demo = cur.fetchone()[0]
            cur.execute("SELECT code FROM app.process_definition ORDER BY code")
            process = [row[0] for row in cur.fetchall()]
            cur.execute("SELECT code FROM app.role ORDER BY code")
            roles = [row[0] for row in cur.fetchall()]
            cur.execute("SELECT code, name FROM control.tenant")
            tenants = cur.fetchall()
        print(f"sin_tenant={empty}")
        print(f"demo_tenant={demo}")
        print(f"tenants={tenants}")
        print(f"process={process}")
        print(f"roles={roles}")
        if empty != 0 or demo != 1 or process != ["ECD", "EJD"]:
            return 1
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
