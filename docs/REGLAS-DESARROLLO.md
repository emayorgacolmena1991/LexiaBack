# Reglas de desarrollo: seguridad y rendimiento

Reglas que salieron de la auditoría de noviembre de 2026 (fases P0–P4 y S-A a S-C). Todo código
nuevo, en backend o frontend, debe cumplirlas. Cada regla dice **qué hacer**, **por qué** y
**dónde está el ejemplo** a copiar. Si una regla no se puede cumplir, se deja escrito el motivo en el
código y en el PR.

---

## 1. Multitenancy y RLS

**1.1 Toda tabla nueva con datos de un tenant lleva `tenant_id NOT NULL` y RLS.**
Misma política que el resto (`tenant_isolation`, ver `V6__integrations_rls.sql`):
```sql
ALTER TABLE app.mi_tabla ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.mi_tabla
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());
```
*Por qué:* `app.documento_texto_ocr` nació sin RLS y con `tenant_id` nulo; cualquier sesión leía el OCR
de otro tenant (corregido en V109).

**1.2 Las consultas filtran también por tenant en el código**, no solo por RLS: `findByIdAndTenantId`,
`WHERE ... AND tenant_id = ?`. RLS es la red; el filtro explícito es la regla.

**1.3 El tenant sale siempre de la sesión** (`AuthContext.require().tenantId()`), nunca de un header,
del body ni de un parámetro. `X-Tenant-Id` solo se lee con `lexia.auth.enabled=false`
(`TenantHeaderFilter`).

**1.4 Cualquier caché o mapa en memoria cuya clave venga del cliente incluye el tenant** en la clave
o valida el tenant al leer. Ejemplos: `OcrSessionCacheService.clave()`,
`CargaDocumentoService.requireDraft()`.

**1.5 Si un ID llega del cliente, se comprueba que pertenece al tenant antes de usarlo**; si no, 404
(no revelar que existe en otro tenant). Ejemplo: `CargaDocumentoService.exigirSesionDelTenant()`.

**1.6 Workers y tareas en segundo plano fijan el tenant del job** (`TenantContext` / `AuthContext`) y
lo limpian en `finally`. Nunca dependen de un contexto heredado.

**1.7 Para leer datos de varios tenants (workers, outbox) se usa una función `SECURITY DEFINER`**
mínima que devuelva solo ids y tenant (patrón `app.outbox_pendientes`, V107), con
`REVOKE ALL ... FROM PUBLIC` y `GRANT EXECUTE ... TO lexia_app`. Después, cada fila se procesa con su
tenant fijado y bajo RLS.

**1.8 No usar `FORCE ROW LEVEL SECURITY`**: rompe esas funciones `SECURITY DEFINER` (login y outbox).
La protección es que `lexia_app` no es dueño de las tablas y tiene `NOBYPASSRLS`.

**1.9 `app.jobs` no tiene RLS** (los workers reclaman de todos los tenants): toda consulta sobre
`app.jobs` que sirva a un usuario filtra por `tenant_id` explícitamente.

## 2. Autorización

**2.1 Tener sesión no es tener permiso.** Toda ruta nueva comprueba un permiso concreto.
`SecurityConfig` da `ROLE_USER` a cualquier sesión, así que `hasAnyAuthority` no basta.

**2.2 Los controladores de expedientes usan `PermisoExpediente.leer(...)` / `.escribir(...)`**
(`expedientes:caso:leer` / `expedientes:caso:escribir`). Otros módulos usan
`AuthorizationService.requirePermission("<modulo>:<recurso>:<accion>")`.

**2.3 El permiso se comprueba en el controlador o en el servicio, nunca en el worker** (que no tiene
sesión de usuario).

**2.4 Los datos de auditoría (quién confirmó, quién creó) salen de la sesión**, nunca del body.
Ejemplo: `IngestaMasivaEscrituracionService.usuarioConfirmacion()` ignora `confirmadoPor`.

## 3. Sesión, CSRF y login

**3.1 CSRF está activo en toda mutación autenticada con cookie.** No añadir rutas a
`ignoringRequestMatchers` salvo endpoints previos a la sesión (login, refresh, recuperación).
Un `POST`/`PUT`/`PATCH`/`DELETE` nuevo debe funcionar con el token.

**3.2 Frontend: el token CSRF lo pone solo `csrfHeaderInterceptor`**, leído de la cookie
`XSRF-TOKEN` en cada petición. No crear listas de exención nuevas ni añadir el header a mano.
Las peticiones van por `HttpClient` (no `fetch`) para pasar por los interceptores.

**3.3 Los tokens se guardan con hash.** La cookie de sesión es un token aleatorio y en BD solo va su
SHA-256 (`UserSession.emitirToken()`, V111); lo mismo con refresh e invitaciones (`TokenHasher`). Ningún
identificador de BD sirve como credencial.

**3.4 No guardar tokens de sesión en `localStorage`** (los lee cualquier XSS). La sesión viaja en la
cookie `HttpOnly`.

**3.5 La IP del cliente es `request.getRemoteAddr()`.** Nunca leer `X-Forwarded-For` a mano: detrás de
un proxy se configura `LEXIA_FORWARD_HEADERS=native` y Tomcat solo acepta el header de proxies de
confianza.

**3.6 Los límites de intentos frenan al atacante, no bloquean al usuario**: 5 fallos por cuenta + IP
dan 429; la cuenta solo se bloquea con 20 fallos desde cualquier IP. Un login con MFA pendiente
(`MFA_REQUIRED`) no cierra la racha de fallos (`LoginAttempt.cierraRachaDeFallos()`).

**3.7 Sin credenciales por defecto en el código ni en `application*.yml`.** Contraseñas, claves de API,
hosts reales y emails de personas van en variables de entorno sin valor por defecto.

## 4. Archivos subidos y descargas

**4.1 El MIME se deduce de la extensión permitida, nunca del `Content-Type` del cliente**
(`CargaDocumentoService.mimeDeExtension()`).

**4.2 Se valida la firma (magic bytes) contra la extensión** (`CargaDocumentoService.exigirFirma()`):
PDF, JPG, PNG, TIFF. Formato nuevo: se añade su firma.

**4.3 Solo PDF e imágenes no SVG se sirven `inline`**; el resto se descarga como
`application/octet-stream` + `attachment` + `X-Content-Type-Options: nosniff`
(`NotaryController.archivo()`, `CoactivaExpedienteController.contenido()`). Un HTML o SVG inline
ejecuta script en el origen de Lexia.

**4.4 `Content-Disposition` se construye con `Descargas.adjunto(nombre)`** o con
`ContentDisposition.builder`; nunca concatenando el nombre.

**4.5 Cada endpoint de subida fija su tope de tamaño** (25 MB documentos, 10 MB captura BIESS, 1 MB
logo). El tope global (`LEXIA_MAX_FILE_SIZE`) es solo la red.

**4.6 Las claves de almacenamiento las genera el servidor** (`ArchivoStorageLocal.resolverNueva()`
rechaza `..`, rutas absolutas y salidas de la raíz). El nombre del cliente nunca forma la ruta.

## 5. Jobs y workers

**5.1 Todo trabajo largo (OCR, LLM) va por `app.jobs` y un worker**, no en el hilo del request.
Si un endpoint tiene que esperar resultados, lleva un tope de elementos (ejemplo:
`OcrAzureBatchService.MAX_DOCUMENTOS_BATCH`).

**5.2 El worker reclama solo con hilo libre y hasta llenarlos** (semáforo de capacidad:
`IngestaJobWorker.poll()`, `CoactivaJobWorker.tick()`). Nunca encolar en un pool sin límite.

**5.3 Reclamar con `FOR UPDATE SKIP LOCKED` y `run_at <= now()`**, y fijar un lease (`locked_until`)
mayor que el timeout del job (`JobRepository.reclamar()`).

**5.4 El timeout del job es mayor que la suma de los timeouts externos que llama** (Claude 180 s,
Azure 120 s por llamada). Configurable por propiedad, no un número fijo en el código.

**5.5 Reintentos con backoff y tope** (`JobRepository.backoffSegundos()`, `max-intentos`). Nunca
reintentar de inmediato ni sin límite.

**5.6 Al fallar de forma definitiva, el worker actualiza la entidad de negocio** (por ejemplo,
`app.ingesta_item` a ERROR/ROJO), no solo `app.jobs`. Ningún fallo se traga en silencio: se registra
y se marca.

**5.7 Las actualizaciones de estado de un job comprueban el estado previo**
(`WHERE id = ? AND estado = 'EN_PROCESO'`), para que un hilo atrasado no pise un job reasignado.

**5.8 Mantener desacoplados los flujos `ingesta` (batch) y `wizard` (secuencial).**

## 6. Hilos, conexiones y scheduler

**6.1 Pools con cola acotada y política de rechazo explícita** (`AsyncConfig.newPool()`:
`CallerRunsPolicy`). Nada de `Executors.newFixedThreadPool` con cola infinita para trabajo de usuarios.

**6.2 Toda tarea asíncrona propaga y restaura el contexto** (auth y tenant) con
`AsyncConfig.wrapWithTenantAuth()`. No usar `MODE_INHERITABLETHREADLOCAL`.

**6.3 `@Scheduled` no bloquea**: el scheduler tiene 5 hilos compartidos. Tareas lentas o con HTTP van a
un pool propio.

**6.4 No retener una conexión del pool durante llamadas HTTP largas**, salvo que el lock sea necesario
(como el outbox) y esté documentado. Las conexiones permanentes (LISTEN) van fuera del pool.

**6.5 El pool de BD es finito** (`LEXIA_DB_POOL_MAX`, 20 por instancia). Al añadir una instancia o un
worker, comprobar que la suma queda bajo `max_connections`.

## 7. Memoria y crecimiento

**7.1 Todo mapa o caché en memoria tiene expiración y tope** (TTL o purga programada). Ejemplos:
`SessionCache` (TTL + 10 000 entradas), borradores (`borrador-ttl-horas`), `OcrSessionCacheService`.

**7.2 SSE con timeout finito, ping periódico y limpieza de la entrada cuando no quedan oyentes**
(`IngestaMasivaEscrituracionService.stream()` / `latidoSse()`).

**7.3 Toda tabla de eventos o de log tiene purga** (ejemplo: `BatchEventPublisherService.purgarEventos()`).

**7.4 Al procesar un elemento de un lote, cargar solo ese elemento**, no el lote entero
(`cargar(tenant, lote, soloFila)`).

**7.5 Nada de `findAll()` sobre tablas que crecen**: consulta derivada con filtro e índice.

## 8. Consultas e índices

**8.1 Toda consulta nueva en un camino frecuente (worker, scheduler, cada request) tiene índice**, y la
migración se crea en el mismo PR.

**8.2 Varios conteos de la misma tabla se hacen en una sola consulta con `FILTER`** (ejemplo:
`IngestaMasivaEscrituracionService.resumen()`).

**8.3 SQL siempre parametrizado.** Si hay que componer SQL, solo con fragmentos fijos del código, nunca
con texto del usuario.

## 9. Logs

**9.1 No registrar contenido de documentos, texto OCR, prompts, respuestas de LLM ni payloads
completos.** Se registran ids y tamaños (`chars=`, `bytes=`).

**9.2 No registrar tokens, cookies, contraseñas ni claves**, ni siquiera en DEBUG.

**9.3 `org.springframework.security` en INFO**; DEBUG solo bajo demanda (`LEXIA_SECURITY_DEBUG`).

## 10. Migraciones

**10.1 Las migraciones aplicadas no se editan**: siempre una versión nueva.

**10.2 Si la migración actualiza filas bajo RLS, verifica antes que el rol que la ejecuta las ve**
(dueño sin `FORCE`, superusuario o `BYPASSRLS`) y, si no, emite `RAISE WARNING` en lugar de fallar
en silencio (patrón V101, V107, V109).

**10.3 `CREATE INDEX` en tablas grandes**: valorar `CONCURRENTLY` (fuera de transacción) o una ventana
tranquila.

## 11. Verificación antes de dar algo por hecho

**11.1 Compilar y correr `mvnw test`** (en esta máquina, con
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp`). Decir explícitamente qué no se probó.

**11.2 Probar el arranque real** contra un Postgres local, no solo los tests: `contextLoads` falla por
otras causas y puede ocultar errores de arranque (pasó con `SessionCache`).

**11.3 Cambios de rendimiento: medir antes y después** con `docs/medicion-rendimiento.md`.

**11.4 Cambios de seguridad: test que pruebe el ataque**, no solo el caso feliz (ejemplos:
`OcrSessionCacheTenantTest`, `NotaryArchivoInlineTest`, `AuthLoginThrottleTest`,
`SecurityConfigCsrfTest`).

---

## Checklist para cada PR

- [ ] Tablas nuevas con `tenant_id NOT NULL` + RLS; consultas filtradas por tenant.
- [ ] Rutas nuevas con permiso concreto (no solo sesión) y CSRF activo.
- [ ] IDs del cliente validados contra el tenant; datos de auditoría desde la sesión.
- [ ] Subidas: MIME por extensión + firma + tope; descargas inline solo PDF e imágenes no SVG.
- [ ] Trabajo largo en un job con timeout, backoff, tope de intentos y error marcado en la entidad.
- [ ] Mapas, cachés, SSE y tablas de eventos con expiración o purga.
- [ ] Consultas frecuentes con índice; sin `findAll()` sobre tablas que crecen.
- [ ] Logs sin contenido de documentos, PII ni secretos.
- [ ] Sin secretos ni hosts reales por defecto en `application*.yml`.
- [ ] `mvnw test` en verde (salvo fallos ya conocidos) y arranque probado; qué no se probó, dicho.
