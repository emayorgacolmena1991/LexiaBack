-- Descripción editable de validaciones V1–V6 (UI administración EJD).

ALTER TABLE app.validation_def
    ADD COLUMN IF NOT EXISTS description VARCHAR(300);

UPDATE app.validation_def
SET description = 'Valida que los documentos requeridos estén presentes en el expediente.'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND code = 'V1';

UPDATE app.validation_def
SET description = 'Comprueba integridad y legibilidad de los documentos cargados.'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND code = 'V2';

UPDATE app.validation_def
SET description = 'Verifica vigencia de certificados y documentos con fecha de caducidad.'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND code = 'V3';

UPDATE app.validation_def
SET description = 'Revisa consistencia interna de datos dentro del expediente.'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND code = 'V4';

UPDATE app.validation_def
SET description = 'Contrasta información con fuentes cruzadas del proceso.'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND code = 'V5';

UPDATE app.validation_def
SET description = 'Evaluación jurídica pendiente de decisión profesional.'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND code = 'V6';
