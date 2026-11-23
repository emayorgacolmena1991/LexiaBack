-- 1) Columnas (ya tienen IF NOT EXISTS, dejar igual)
ALTER TABLE app.ingesta_lote
  ADD COLUMN IF NOT EXISTS producto VARCHAR(64),
  ADD COLUMN IF NOT EXISTS canton VARCHAR(64),
  ADD COLUMN IF NOT EXISTS status_revision VARCHAR(32) NOT NULL DEFAULT 'BORRADOR',
  ADD COLUMN IF NOT EXISTS confirmado_por UUID,
  ADD COLUMN IF NOT EXISTS confirmado_at TIMESTAMPTZ;

-- 2) Constraint guardada por DO block
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'chk_ingesta_lote_status'
  ) THEN
    ALTER TABLE app.ingesta_lote
      ADD CONSTRAINT chk_ingesta_lote_status
      CHECK (status_revision IN ('BORRADOR','CONFIRMADO'));
  END IF;
END $$;

-- 3) Índices (ya tienen IF NOT EXISTS, dejar igual)
CREATE INDEX IF NOT EXISTS idx_ingesta_lote_tenant_created
  ON app.ingesta_lote (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ingesta_lote_status
  ON app.ingesta_lote (tenant_id, status_revision);

-- 4) Backfill (dejar igual, ya tiene WHERE l.producto IS NULL)
UPDATE app.ingesta_lote l
SET producto = sub.producto,
    canton   = sub.canton
FROM (
  SELECT DISTINCT ON (lote_id)
    lote_id,
    COALESCE(detalle->>'productCode', detalle->>'producto') AS producto,
    detalle->>'canton' AS canton
  FROM app.ingesta_item
  ORDER BY lote_id, client_row_id
) sub
WHERE l.id = sub.lote_id
  AND l.producto IS NULL
  AND l.canton IS NULL;
