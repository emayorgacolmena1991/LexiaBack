-- S12: la cookie de sesión era la PK de app.user_session. Quien leyera la tabla (copia, réplica,
-- usuario de solo lectura) podía usar cualquier sesión activa. Ahora la cookie es un token aleatorio
-- y aquí solo se guarda su SHA-256. Las sesiones previas (token_hash NULL) siguen válidas hasta que
-- caducan (session-hours) y no se pueden crear nuevas así.
ALTER TABLE app.user_session ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_session_token_hash
    ON app.user_session (token_hash)
    WHERE token_hash IS NOT NULL;
