package com.lexia.api.modules.notifications;

public final class EmailLayout {

  private EmailLayout() {}

  public static String wrap(String title, String bodyHtml, String footerNote) {
    return """
        <!DOCTYPE html>
        <html lang="es">
        <head>
          <meta charset="UTF-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
          <title>%s</title>
        </head>
        <body style="margin:0;padding:0;background:#f4f7fb;font-family:Segoe UI,Helvetica,Arial,sans-serif;color:#0b2d55;">
          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f7fb;padding:32px 16px;">
            <tr>
              <td align="center">
                <table role="presentation" width="560" cellspacing="0" cellpadding="0" style="max-width:560px;background:#ffffff;border:1px solid #dbe4f0;border-radius:12px;overflow:hidden;">
                  <tr>
                    <td style="background:linear-gradient(135deg,#0b2d55 0%%,#1e6fd9 100%%);padding:24px 28px;">
                      <p style="margin:0;font-size:12px;letter-spacing:0.08em;text-transform:uppercase;color:#9fd6ff;">LEXIA</p>
                      <h1 style="margin:8px 0 0;font-size:22px;line-height:1.3;color:#ffffff;font-weight:600;">%s</h1>
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:28px;font-size:15px;line-height:1.6;color:#334155;">
                      %s
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:0 28px 24px;font-size:12px;line-height:1.5;color:#64748b;">
                      %s
                    </td>
                  </tr>
                </table>
                <p style="margin:16px 0 0;font-size:11px;color:#94a3b8;">Plataforma Legal Inteligente · LEXIA</p>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """
        .formatted(title, title, bodyHtml, footerNote);
  }

  public static String button(String label, String url) {
    return """
        <p style="margin:24px 0;">
          <a href="%s" style="display:inline-block;background:#00c2a8;color:#ffffff;text-decoration:none;font-weight:600;padding:12px 20px;border-radius:8px;">
            %s
          </a>
        </p>
        """
        .formatted(url, label);
  }

  public static String infoBox(String content) {
    return """
        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="margin:16px 0;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;">
          <tr><td style="padding:14px 16px;font-size:14px;color:#475569;">%s</td></tr>
        </table>
        """
        .formatted(content);
  }
}
