-- Observaciones del estudio: solo problemas que requieren acción y ligados a un documento.
--  - documentos_relacionados: otros LegalDocument (ids) del mismo problema; ya validados contra el
--    expediente al guardar.
--  - tipo_documento_faltante: código del catálogo del producto cuando el problema es que falta un
--    documento (entonces legal_document_id es null).
--  - title_study.validaciones: lo que la IA verificó como correcto. Nunca se convierte en
--    observación.
-- Las tablas ya tienen RLS (V6); las columnas nuevas quedan cubiertas.
ALTER TABLE app.title_observation
    ADD COLUMN documentos_relacionados JSONB,
    ADD COLUMN tipo_documento_faltante VARCHAR(64);

ALTER TABLE app.title_study
    ADD COLUMN validaciones JSONB;
