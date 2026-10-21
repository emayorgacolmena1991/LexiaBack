CREATE TABLE app.sse_evento (
    id            UUID PRIMARY KEY,
    expediente_id UUID NOT NULL,
    tipo          VARCHAR(40) NOT NULL,
    cuerpo        TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, DELETE ON TABLE app.sse_evento TO lexia_app;
GRANT SELECT ON TABLE app.sse_evento TO lexia_readonly;
