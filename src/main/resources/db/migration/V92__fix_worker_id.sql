UPDATE app.jobs SET worker_id = 'local-dev-worker' WHERE worker_id IS NULL;

-- Los jobs vivos de ingesta quedarían huérfanos con el nuevo worker_id: se cancelan y sus items
-- pasan a ERROR. iaEstado también, porque en EN_PROCESO el frontend seguiría viendo el lote activo.
UPDATE app.ingesta_item
   SET estado = 'ERROR',
       error_log = 'Job cancelado al migrar worker_id. Reintentar o reemplazar.',
       detalle = jsonb_set(
         jsonb_set(
           jsonb_set(
             jsonb_set(detalle, '{ocrEstado}', '"ERROR"'),
             '{semaforo}', '"ROJO"'),
           '{iaEstado}', '"ERROR"'),
         '{observaciones}', '"Job cancelado al migrar worker_id. Reintentar o reemplazar."')
 WHERE estado = 'EN_PROCESO';

DELETE FROM app.jobs
 WHERE tipo IN ('PROCESAR_DOCUMENTO', 'PROCESAR_IA_EXPEDIENTE')
   AND estado IN ('PENDIENTE', 'EN_PROCESO');
