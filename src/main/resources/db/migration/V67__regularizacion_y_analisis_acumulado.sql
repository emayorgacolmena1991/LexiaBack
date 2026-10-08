-- Regularización de escrituración: corta el reenvío automático mientras el usuario corrige.
ALTER TABLE app.writing_file
    ADD COLUMN estado_regularizacion VARCHAR(50) NOT NULL DEFAULT 'PROCESO_REGULAR',
    ADD COLUMN flag_bloqueo_reenvio BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE app.writing_file
    ADD CONSTRAINT writing_file_estado_regularizacion_check
        CHECK (estado_regularizacion IN (
            'PROCESO_REGULAR', 'EN_REGULARIZACION', 'CORREGIDO_POR_USUARIO'));

CREATE INDEX idx_writing_file_regularizacion
    ON app.writing_file (estado_regularizacion, flag_bloqueo_reenvio);

-- Diagnóstico de coactivas: cada corrida apunta al consolidado anterior.
ALTER TABLE app.coactiva_analisis
    ADD COLUMN es_consolidado BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN analisis_padre_id UUID REFERENCES app.coactiva_analisis (id);
