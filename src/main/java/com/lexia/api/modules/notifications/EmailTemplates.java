package com.lexia.api.modules.notifications;

public final class EmailTemplates {

  private EmailTemplates() {}

  public static String userInvitation(String displayName, String inviteUrl, String tenantName) {
    String body =
        """
        <p>Hola <strong>%s</strong>,</p>
        <p>Te invitaron a unirte a <strong>%s</strong> en LEXIA, la plataforma legal inteligente de tu organización.</p>
        <p>Para activar tu cuenta, crea tu contraseña con el botón de abajo. El enlace vence en <strong>72 horas</strong>.</p>
        %s
        <p style="margin-top:20px;font-size:13px;color:#64748b;">Si el botón no funciona, copia este enlace en tu navegador:<br/><span style="word-break:break-all;">%s</span></p>
        """
            .formatted(
                displayName,
                tenantName,
                EmailLayout.button("Activar mi cuenta", inviteUrl),
                inviteUrl);
    return EmailLayout.wrap(
        "Invitación a LEXIA",
        body,
        "Si no esperabas este correo, puedes ignorarlo con seguridad.");
  }

  public static String passwordChanged(String displayName) {
    String body =
        """
        <p>Hola <strong>%s</strong>,</p>
        <p>Tu contraseña de LEXIA fue actualizada correctamente.</p>
        %s
        <p>Si no fuiste tú, contacta de inmediato al administrador de tu organización y revisa la actividad reciente de tu cuenta.</p>
        """
            .formatted(
                displayName,
                EmailLayout.infoBox("Este es un aviso de seguridad automático. No necesitas hacer nada si fuiste tú."));
    return EmailLayout.wrap(
        "Contraseña actualizada",
        body,
        "Nunca compartas tu contraseña ni códigos de verificación con terceros.");
  }

  public static String passwordReset(String displayName, String resetUrl) {
    String body =
        """
        <p>Hola <strong>%s</strong>,</p>
        <p>Recibimos una solicitud para restablecer la contraseña de tu cuenta en LEXIA.</p>
        %s
        <p>El enlace vence en <strong>1 hora</strong>. Si no solicitaste este cambio, ignora este mensaje; tu contraseña actual seguirá vigente.</p>
        <p style="margin-top:20px;font-size:13px;color:#64748b;">Enlace alternativo:<br/><span style="word-break:break-all;">%s</span></p>
        """
            .formatted(displayName, EmailLayout.button("Restablecer contraseña", resetUrl), resetUrl);
    return EmailLayout.wrap(
        "Restablecer contraseña",
        body,
        "Por seguridad, este enlace solo puede usarse una vez.");
  }

  public static String loginAlert(String displayName, String when, String ip, String userAgent) {
    String body =
        """
        <p>Hola <strong>%s</strong>,</p>
        <p>Detectamos un ingreso exitoso a tu cuenta en LEXIA.</p>
        %s
        <p>Si reconoces este acceso, no necesitas hacer nada. Si no fuiste tú, cambia tu contraseña de inmediato y avisa al administrador.</p>
        """
            .formatted(
                displayName,
                EmailLayout.infoBox(
                    "<strong>Fecha:</strong> "
                        + when
                        + "<br/><strong>IP:</strong> "
                        + ip
                        + "<br/><strong>Dispositivo:</strong> "
                        + userAgent));
    return EmailLayout.wrap(
        "Nuevo ingreso detectado",
        body,
        "Puedes desactivar estas alertas en Seguridad → Notificaciones.");
  }
}
