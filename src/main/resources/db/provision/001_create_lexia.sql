-- Provisionamiento de la base LEXIA (sin contraseñas).
-- Ejecutar conectado a la base `postgres` como rol bootstrap.
-- Las contraseñas de lexia_migrator / lexia_app / lexia_readonly las inyecta provision.py.

CREATE ROLE lexia_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE lexia_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
CREATE ROLE lexia_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;

CREATE DATABASE lexia
    OWNER lexia_migrator
    ENCODING 'UTF8'
    LOCALE_PROVIDER 'libc'
    LC_COLLATE 'en_US.UTF-8'
    LC_CTYPE 'en_US.UTF-8'
    TEMPLATE template0;

ALTER DATABASE lexia SET timezone TO 'America/Bogota';

REVOKE ALL ON DATABASE lexia FROM PUBLIC;
GRANT CONNECT ON DATABASE lexia TO lexia_migrator, lexia_app, lexia_readonly;
GRANT CREATE ON DATABASE lexia TO lexia_migrator;
