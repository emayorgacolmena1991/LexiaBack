-- Datos estructurados de la minuta. Reemplaza el JSON efímero {draftId}_datos.json.
ALTER TABLE app.minuta_draft
    ADD COLUMN IF NOT EXISTS payload JSONB;

COMMENT ON COLUMN app.minuta_draft.payload IS
    'MinutaViviendaData en JSON. Fuente de hidratación al refrescar o cambiar de etapa.';
