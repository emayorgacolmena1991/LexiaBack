package com.lexia.api.modules.notifications;

public interface EmailClient {
  void send(String toEmail, String toName, String subject, String htmlContent);
}
