# Medición de rendimiento (P0)

Cómo tomar una foto del rendimiento antes y después de un cambio. Las dos herramientas son de
solo lectura.

## 1. Métricas de la app (`/actuator/metrics`)

Solo responden **desde la propia máquina** (`127.0.0.1` / `::1`). Desde cualquier otra IP dan 401/403
(`SecurityConfig.metricasDesdeLoopback`). En el servidor:

```powershell
.\scripts\medir-metricas.ps1 -Puerto 8081
```

Guarda `medicion-<fecha>.json`. Tómala en el mismo momento del día (con carga parecida) antes y
después del despliegue, y compara.

| Métrica | Qué mide | Señal de problema |
|---|---|---|
| `hikaricp.connections.active` / `.pending` / `.timeout` | Uso del pool de BD (máx. `LEXIA_DB_POOL_MAX`) | `pending > 0` sostenido o `timeout` creciendo |
| `executor.active` / `executor.queued` `{name}` | Pools `ingesta-worker`, `AzureOCR`, `lexia-async`, `coactiva-anexo` | `queued` creciendo sin bajar |
| `lexia.worker.hilos.ocupados{worker}` | Hilos en uso de ingesta (5) y coactivas (4) | Siempre al máximo con cola alta: falta capacidad |
| `lexia.jobs{tipo,estado}` | Jobs `PENDIENTE` / `EN_PROCESO` ahora; `ERROR` creados en 24 h | `ERROR` subiendo |
| `lexia.jobs.espera.segundos{tipo}` | Antigüedad del pendiente más viejo (latencia de cola) | Minutos con hilos libres: el job es de otro nodo (`worker_id`) |
| `lexia.jobs.duracion{worker,tipo,resultado}` | Duración de cada job; `resultado` = `ok`, `timeout`, `terminal`, `error` | `timeout` frecuente: revisar `LEXIA_INGESTA_TIMEOUT_*` |
| `lexia.memoria.borradores` / `.bytes` | Borradores (y sus archivos) retenidos en RAM | Bytes creciendo sin bajar en 24 h |
| `lexia.memoria.sesiones.cache` | Sesiones en la caché de autenticación | Cerca de 10 000 |
| `lexia.sse.oyentes{flujo}` | Lotes / expedientes con navegadores conectados por SSE | Crece sin usuarios activos |
| `http.server.requests` | Latencia y volumen HTTP (filtrable por `uri`, `status`) | `max` alto en rutas concretas |

Consultar una métrica suelta:

```powershell
Invoke-RestMethod "http://localhost:8081/actuator/metrics/lexia.jobs?tag=estado:PENDIENTE"
```

## 2. SQL de solo lectura

```powershell
psql -h <host> -U lexia_readonly -d lexia -f scripts/medicion-rendimiento.sql > medicion-sql-<fecha>.txt
```

Incluye: cola por worker/tipo/estado, latencia de cola, throughput de jobs en 24 h, tiempo hasta
confirmar lotes, tamaño de las tablas que solo crecen, conexiones por usuario frente a
`max_connections` y consultas más costosas.

La consulta 7 necesita la extensión `pg_stat_statements` y que `lexia_readonly` tenga
`pg_read_all_stats` (lo concede un DBA); si no, esa sección da error y el resto sigue.

## 3. Cómo comparar

1. Antes del despliegue: script de métricas + SQL, guardar ambos.
2. Desplegar.
3. Con carga parecida (mismo tramo horario): repetir y comparar sobre todo
   `lexia.jobs.espera.segundos`, `lexia.jobs.duracion`, `hikaricp.connections.pending` y la
   consulta 3 del SQL (jobs/hora y segundos desde encolado).
