ALTER TABLE app.gate_def
    ADD COLUMN IF NOT EXISTS response_type VARCHAR(16) NOT NULL DEFAULT 'yes_no',
    ADD COLUMN IF NOT EXISTS continue_criterion VARCHAR(16) NOT NULL DEFAULT 'affirmative',
    ADD COLUMN IF NOT EXISTS mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS message_ok VARCHAR(500),
    ADD COLUMN IF NOT EXISTS message_fail VARCHAR(500),
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE app.gate_def
SET message_ok = 'La documentación requerida está completa.',
    message_fail = 'Completa la documentación requerida para continuar.'
WHERE code = 'G-001';
