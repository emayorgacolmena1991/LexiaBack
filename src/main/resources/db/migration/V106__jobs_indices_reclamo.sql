-- Reclamo de ingesta: PENDIENTE por tipo y worker, en orden de prioridad (JobRepository.reclamar).
CREATE INDEX IF NOT EXISTS ix_jobs_pendientes_worker
    ON app.jobs (tipo, worker_id, prioridad DESC, created_at)
    WHERE estado = 'PENDIENTE';

-- Limpieza de colgados: EN_PROCESO de un worker con lock vencido (JobRepository.liberarJobsColgados).
CREATE INDEX IF NOT EXISTS ix_jobs_en_proceso_lock
    ON app.jobs (worker_id, locked_until)
    WHERE estado = 'EN_PROCESO';

-- Jobs vivos de un lote (JobRepository.enColaDelLote).
CREATE INDEX IF NOT EXISTS ix_jobs_vivos_lote
    ON app.jobs (tenant_id, (payload->>'loteId'))
    WHERE estado IN ('PENDIENTE', 'EN_PROCESO');
