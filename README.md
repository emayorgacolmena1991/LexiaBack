# LEXIA API

Ambiente backend de **LEXIA — Plataforma Legal Inteligente**.

Cimiento de persistencia multi-tenant con login de sesión, bloqueo y TOTP opcional. No implementa reglas jurídicas ni conectores.

## Stack

- Java 17
- Spring Boot 4
- PostgreSQL (`lexia` en el servidor acordado)
- Flyway + JPA (cimiento)
- Maven Wrapper (`mvnw` / `mvnw.cmd`)
- Puerto `8081` (variable `LEXIA_SERVER_PORT`)

## Estructura

```text
backend/
├── src/main/java/com/lexia/api/
│   ├── common/api/          health
│   ├── config/              CORS + Security
│   └── modules/
│       ├── tenancy/         TenantContext + RLS
│       ├── identity/        usuarios, roles, auditoría
│       └── auth/            login, sesión, TOTP
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

Requiere `backend/.env` (gitignored) o variables `LEXIA_DB_*` y `LEXIA_FLYWAY_*`.
La app carga `backend/.env` automáticamente al arrancar (IntelliJ incluido).

```bash
cd backend
.\mvnw.cmd spring-boot:run
```

Health:

```text
GET http://127.0.0.1:8081/api/v1/health
GET http://127.0.0.1:8081/actuator/health
```

## Login y 2FA

Cookies `HttpOnly` + `SameSite=Lax` (`LEXIA_SID`, `LEXIA_RT`). CSRF en cookie `XSRF-TOKEN` (el login inicial está exento). Contraseñas Argon2id. Bloqueo a los 5 fallos / 30 min. TOTP opcional cifrado AES-GCM.

| Ruta | Uso |
|---|---|
| `POST /api/v1/auth/login` | Correo + contraseña. Puede devolver `MFA_REQUIRED` o `TENANT_SELECTION_REQUIRED` |
| `POST /api/v1/auth/select-tenant` | Tras multi-org, elige tenant con `challengeId` + `tenantId` |
| `GET /api/v1/auth/tenant-selection/{challengeId}/options` | Opciones de org para un reto de selección |
| `POST /api/v1/auth/mfa/verify` | Código TOTP o de recuperación |
| `GET /api/v1/auth/me` | Sesión actual |
| `POST /api/v1/auth/logout` | Cierra sesión |
| `POST /api/v1/auth/mfa/enroll` | Genera secreto TOTP |
| `POST /api/v1/auth/mfa/confirm` | Activa 2FA y entrega códigos de recuperación |
| `POST /api/v1/auth/mfa/disable` | Apaga 2FA (pide un código) |

Usuario demo: `laura.gomez@lexia.demo` (rol `ABOGADO_SENIOR`). Contraseña: `LEXIA_DEMO_PASSWORD` (por defecto `Lexia-Demo-2026!`). En producción define `LEXIA_CRYPTO_KEY` y `LEXIA_COOKIE_SECURE=true`.

Para ver un expediente coactivo: con este API en `8081` y el frontend en `4200`, inicia sesión en `/login` y abre `/coactivas`. El detalle está en `/coactivas/expedientes/{id}`. El frontend no omite el guard de sesión.

## Pruebas

```bash
.\mvnw.cmd test
```

`RlsIsolationIT` se omite si no hay `LEXIA_DB_HOST` / `LEXIA_DB_PASSWORD`.

### CI / E2E local

- Perfil Spring **`ci`**: `application-ci.yml` (PostgreSQL local, Flyway desde V1, `LEXIA_E2E_ENABLED=true`).
- Scripts: `scripts/ci-init-db.sh`, `scripts/ci-post-migrate.sql`, `scripts/run-ci-e2e-local.sh`.
- Docker: `docker-compose.ci.yml` (Postgres 16 efímero).
- Workflow GitHub: `.github/workflows/ci.yml` (backend test + frontend build + Playwright).

### Multi-tenant y confianza E2E (bootstrap)

Operación login multi-org, tenants de `emayorga@colmenas.ec`, checklist manual y scripts Playwright: **[docs/operacion-multi-tenant.md](../docs/operacion-multi-tenant.md)**.

En **producción** mantener `LEXIA_E2E_ENABLED=false`. Con E2E activo (solo dev/CI), `LEXIA_E2E_TRUST_BOOTSTRAP_EMAILS` (por defecto igual a `LEXIA_E2E_ENABLED`) permite omitir contraseña y MFA en login y en `POST /api/v1/auth/e2e/session` para correos en `LEXIA_AUTH_BOOTSTRAP_EMAILS` y cuentas `*@lexia.demo`. Eso no debe estar habilitado en ambientes expuestos a usuarios reales.

## Fuera de alcance

- OAuth / SSO corporativo
- OCR, Document AI, LLM
- Motor de reglas
- Conectores QUIPUX, notaría, municipio, registro, firma, pagos
