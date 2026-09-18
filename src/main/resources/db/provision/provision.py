"""Crea la base lexia y los roles de aplicación. No imprime secretos."""

from __future__ import annotations

import os
import secrets
import string
import sys
from pathlib import Path

try:
    import psycopg
    from psycopg import sql
except ImportError:
    sys.stderr.write("Instala psycopg: python -m pip install psycopg[binary]\n")
    raise

ALPHABET = string.ascii_letters + string.digits


def password(existing: str | None) -> str:
    if existing:
        return existing
    return "".join(secrets.choice(ALPHABET) for _ in range(28))


def role_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (name,))
    return cur.fetchone() is not None


def db_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (name,))
    return cur.fetchone() is not None


def main() -> int:
    host = os.environ.get("LEXIA_DB_HOST", "51.79.11.21")
    port = int(os.environ.get("LEXIA_DB_PORT", "5432"))
    bootstrap_user = os.environ.get("LEXIA_BOOTSTRAP_USER", "postgres")
    bootstrap_password = os.environ.get("LEXIA_BOOTSTRAP_PASSWORD")
    if not bootstrap_password:
        sys.stderr.write("Falta LEXIA_BOOTSTRAP_PASSWORD\n")
        return 2

    migrator_password = password(os.environ.get("LEXIA_FLYWAY_PASSWORD"))
    app_password = password(os.environ.get("LEXIA_DB_PASSWORD"))
    readonly_password = password(os.environ.get("LEXIA_READONLY_PASSWORD"))

    conn = psycopg.connect(
        host=host,
        port=port,
        user=bootstrap_user,
        password=bootstrap_password,
        dbname="postgres",
        autocommit=True,
        connect_timeout=15,
    )
    try:
        with conn.cursor() as cur:
            for role, pwd in (
                ("lexia_migrator", migrator_password),
                ("lexia_app", app_password),
                ("lexia_readonly", readonly_password),
            ):
                if not role_exists(cur, role):
                    cur.execute(
                        f"CREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        "NOINHERIT NOBYPASSRLS"
                    )
                    print(f"rol creado: {role}")
                else:
                    print(f"rol ya existia: {role}")
                cur.execute(
                    sql.SQL("ALTER ROLE {} PASSWORD {}").format(
                        sql.Identifier(role), sql.Literal(pwd)
                    )
                )

            cur.execute("ALTER ROLE lexia_app INHERIT")
            cur.execute("ALTER ROLE lexia_readonly INHERIT")

            if not db_exists(cur, "lexia"):
                try:
                    cur.execute(
                        "CREATE DATABASE lexia OWNER lexia_migrator ENCODING 'UTF8' TEMPLATE template0"
                    )
                except Exception:
                    cur.execute("CREATE DATABASE lexia OWNER lexia_migrator ENCODING 'UTF8'")
                print("base creada: lexia")
            else:
                print("base ya existia: lexia")

            cur.execute("ALTER DATABASE lexia OWNER TO lexia_migrator")
            cur.execute("ALTER DATABASE lexia SET timezone TO 'America/Bogota'")
            cur.execute("REVOKE ALL ON DATABASE lexia FROM PUBLIC")
            cur.execute(
                "GRANT CONNECT ON DATABASE lexia TO lexia_migrator, lexia_app, lexia_readonly"
            )
            cur.execute("GRANT CREATE ON DATABASE lexia TO lexia_migrator")
    finally:
        conn.close()

    backend_dir = Path(__file__).resolve().parents[5]
    root_dir = backend_dir.parent
    content = (
        f"LEXIA_DB_HOST={host}\n"
        f"LEXIA_DB_PORT={port}\n"
        f"LEXIA_DB_NAME=lexia\n"
        f"LEXIA_DB_USER=lexia_app\n"
        f"LEXIA_DB_PASSWORD={app_password}\n"
        f"LEXIA_FLYWAY_USER=lexia_migrator\n"
        f"LEXIA_FLYWAY_PASSWORD={migrator_password}\n"
        f"LEXIA_READONLY_USER=lexia_readonly\n"
        f"LEXIA_READONLY_PASSWORD={readonly_password}\n"
        f"SPRING_DATASOURCE_URL=jdbc:postgresql://{host}:{port}/lexia\n"
        f"SPRING_DATASOURCE_USERNAME=lexia_app\n"
        f"SPRING_DATASOURCE_PASSWORD={app_password}\n"
        f"SPRING_FLYWAY_USER=lexia_migrator\n"
        f"SPRING_FLYWAY_PASSWORD={migrator_password}\n"
    )
    (backend_dir / ".env").write_text(content, encoding="utf-8")
    (root_dir / ".env").write_text(content, encoding="utf-8")
    print("env escrito en backend/.env y .env de raiz (gitignored)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
