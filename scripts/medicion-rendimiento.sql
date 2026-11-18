-- P0 · Medición de rendimiento. SOLO LECTURA: se puede correr en cualquier entorno.
-- Uso: psql -h <host> -U lexia_readonly -d lexia -f scripts/medicion-rendimiento.sql
-- Guardar la salida con fecha y comparar antes/después de cada despliegue.

\echo '== 1. Cola de jobs por worker, tipo y estado'
SELECT worker_id, tipo, estado, count(*) AS jobs, max(intentos) AS max_intentos
  FROM app.jobs
 GROUP BY worker_id, tipo, estado
 ORDER BY worker_id, tipo, estado;

\echo '== 2. Latencia de cola: espera del PENDIENTE más viejo (segundos)'
SELECT tipo, count(*) AS pendientes,
       round(EXTRACT(EPOCH FROM now() - min(created_at))) AS espera_max_s
  FROM app.jobs
 WHERE estado = 'PENDIENTE'
 GROUP BY tipo;

\echo '== 3. Throughput y duración de jobs completados (últimas 24 h, aproximado por run_at)'
SELECT tipo, estado, count(*) AS jobs,
       round(avg(EXTRACT(EPOCH FROM run_at - created_at))) AS seg_promedio_desde_encolado
  FROM app.jobs
 WHERE created_at > now() - INTERVAL '24 hours'
   AND estado IN ('COMPLETADO', 'ERROR')
 GROUP BY tipo, estado
 ORDER BY tipo, estado;

\echo '== 4. Lotes de ingesta: tamaño y tiempo hasta confirmar (últimos 30 días)'
SELECT count(*) AS lotes,
       round(avg(total_filas), 1) AS filas_promedio,
       round(avg(EXTRACT(EPOCH FROM confirmado_at - created_at)) / 60) AS min_hasta_confirmar
  FROM app.ingesta_lote
 WHERE created_at > now() - INTERVAL '30 days';

\echo '== 5. Tablas que solo crecen'
SELECT relname AS tabla, n_live_tup AS filas,
       pg_size_pretty(pg_total_relation_size(relid)) AS tamano
  FROM pg_stat_user_tables
 WHERE schemaname = 'app'
   AND relname IN ('jobs', 'sse_evento', 'login_attempt', 'user_session', 'documento_texto_ocr',
                   'ingesta_item', 'audit_event', 'outbox_event')
 ORDER BY pg_total_relation_size(relid) DESC;

\echo '== 6. Conexiones abiertas por usuario y estado'
SELECT usename, state, count(*) AS conexiones,
       (SELECT setting FROM pg_settings WHERE name = 'max_connections') AS max_connections
  FROM pg_stat_activity
 WHERE datname = current_database()
 GROUP BY usename, state
 ORDER BY conexiones DESC;

\echo '== 7. Consultas más costosas (requiere la extensión pg_stat_statements)'
SELECT round(total_exec_time::numeric) AS ms_total, calls, round(mean_exec_time::numeric, 1) AS ms_media,
       left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS consulta
  FROM pg_stat_statements
 WHERE query ILIKE '%app.%'
 ORDER BY total_exec_time DESC
 LIMIT 15;

\echo '== 8. Índices de P1-P4 presentes'
SELECT indexname
  FROM pg_indexes
 WHERE schemaname = 'app'
   AND indexname IN ('ix_jobs_pendientes_worker', 'ix_jobs_en_proceso_lock', 'ix_jobs_vivos_lote',
                     'ix_outbox_pendientes_tipo', 'ix_sse_evento_created',
                     'ix_login_attempt_email_ip_time', 'ux_user_session_token_hash');
