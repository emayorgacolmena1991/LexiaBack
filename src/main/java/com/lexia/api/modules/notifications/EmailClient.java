package com.lexia.api.modules.notifications;

import java.util.List;

public interface EmailClient {

  void send(String toEmail, String toName, String subject, String htmlContent);

  default void sendWithAttachments(
      String toEmail,
      String toName,
      String subject,
      String htmlContent,
      List<EmailAttachment> attachments) {
    send(toEmail, toName, subject, htmlContent);
  }
}
