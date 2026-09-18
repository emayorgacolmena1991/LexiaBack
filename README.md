# LEXIA API

Ambiente backend de **LEXIA — Plataforma Legal Inteligente**.

Cimiento de persistencia multi-tenant. No implementa autenticación productiva, reglas jurídicas ni conectores.

## Stack

- Java 17
- Spring Boot 4
- PostgreSQL (`lexia` en el servidor acordado)
- Flyway + JPA (cimiento)
- Maven Wrapper (`mvnw` / `mvnw.cmd`)
- Puerto `8080`

## Estructura

```text
backend/
├── src/main/java/com/lexia/api/
│   ├── common/api/          health
│   ├── config/              CORS
│   └── modules/
│       ├── tenancy/         TenantContext + RLS
│       └── identity/        usuarios, roles, auditoría
├── src/main/resources/
│   ├── application.yml      datasource por LEXIA_DB_*
│   └── db/
│       ├── migration/       V1–V7
│       └── provision/       alta de base y roles
└── docker-compose.yml       Postgres local opcional
```

## Provisionar (una vez)

```bash
$env:LEXIA_BOOTSTRAP_PASSWORD="..."
python src/main/resources/db/provision/provision.py
python src/main/resources/db/provision/apply_migrations.py
```

Escribe `backend/.env` (gitignored).

## Ejecutar

Requiere `.env` (gitignored) o variables `LEXIA_DB_*` y `LEXIA_FLYWAY_*`.

```bash
cd backend
.\mvnw.cmd spring-boot:run
```

Health:

```text
GET http://127.0.0.1:8080/api/v1/health
GET http://127.0.0.1:8080/actuator/health
```

## Pruebas

```bash
.\mvnw.cmd test
```

`RlsIsolationIT` se omite si no hay `LEXIA_DB_HOST` / `LEXIA_DB_PASSWORD`.

## Fuera de alcance

- OAuth / login real
- OCR, Document AI, LLM
- Motor de reglas
- Conectores QUIPUX, notaría, municipio, registro, firma, pagos
