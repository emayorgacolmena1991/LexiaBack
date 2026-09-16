# LEXIA API

Ambiente backend de **LEXIA — Plataforma Legal Inteligente**.

Esta fase crea el entorno Java. No implementa persistencia, autenticación productiva, reglas jurídicas ni integraciones.

## Stack

- Java 17
- Spring Boot 4
- Maven Wrapper (`mvnw` / `mvnw.cmd`) — no requiere Maven global
- Puerto `8080`

## Estructura

```text
backend/
├── src/main/java/com/lexia/api/
│   ├── LexiaApiApplication.java
│   ├── common/api/          health y contratos transversales
│   ├── config/              CORS hacia Angular
│   └── modules/             reservado: expedientes, escrituración, coactivas
└── src/main/resources/application.yml
```

## Ejecutar

Desde `backend/`:

```bash
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

## Fuera de alcance

- Base de datos
- OAuth / autenticación real
- OCR, Document AI, LLM
- Rules Engine / Workflow
- QUIPUX, notaría, municipio, registro, firma, pagos
