-- Purga periódica de eventos SSE ya entregados (BatchEventPublisherService.purgarEventos).
CREATE INDEX IF NOT EXISTS ix_sse_evento_created ON app.sse_evento (created_at);
