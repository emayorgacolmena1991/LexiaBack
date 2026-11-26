package com.lexia.api.modules.notifications;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lexia.brevo.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingEmailClient implements EmailClient {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingEmailClient.class);

  @Override
  public void send(String toEmail, String toName, String subject, String htmlContent) {
    LOG.info(
        "[email-dev] to={} subject={} preview={}",
        toEmail,
        subject,
        htmlContent.length() > 120 ? htmlContent.substring(0, 120) + "…" : htmlContent);
  }

  @Override
  public void sendWithAttachments(
      String toEmail,
      String toName,
      String subject,
      String htmlContent,
      List<EmailAttachment> attachments) {
    LOG.info(
        "[email-dev] to={} subject={} attachments={}",
        toEmail,
        subject,
        attachments == null ? 0 : attachments.size());
    send(toEmail, toName, subject, htmlContent);
  }
}
