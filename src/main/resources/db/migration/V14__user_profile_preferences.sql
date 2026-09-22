ALTER TABLE app.app_user
    ADD COLUMN locale VARCHAR(10) NOT NULL DEFAULT 'es',
    ADD COLUMN timezone VARCHAR(64),
    ADD COLUMN theme VARCHAR(16) NOT NULL DEFAULT 'light';

ALTER TABLE app.app_user
    ADD CONSTRAINT app_user_theme_check CHECK (theme IN ('light', 'dark', 'system'));

ALTER TABLE app.app_user
    ADD CONSTRAINT app_user_locale_check CHECK (locale IN ('es', 'en'));
