-- Freno por cuenta + IP en el login (AuthService.rejectIfEmailIpThrottled).
CREATE INDEX IF NOT EXISTS ix_login_attempt_email_ip_time
    ON app.login_attempt (email, ip_address, created_at DESC);
