-- Filas que se quedaron en ANALIZANDO (worker muerto o update no commiteado).
-- El front polleaba GET /archivos sin salir del spinner.
UPDATE app.coactiva_archivo
SET estado_ia = 'ERROR',
    motivo_rechazo_ia = 'Análisis interrumpido. Vuelve a cargar el documento.'
WHERE estado_ia = 'ANALIZANDO'
  AND deleted_at IS NULL;
