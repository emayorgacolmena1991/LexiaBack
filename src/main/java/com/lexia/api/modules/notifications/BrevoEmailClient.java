package com.lexia.api.modules.notifications;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "lexia.brevo.enabled", havingValue = "true")
public class BrevoEmailClient implements EmailClient {

  private static final Logger LOG = LoggerFactory.getLogger(BrevoEmailClient.class);
  private static final String API_URL = "https://api.brevo.com/v3/smtp/email";

  private final RestClient restClient;
  private final BrevoProperties properties;

  public BrevoEmailClient(BrevoProperties properties) {
    this.properties = properties;
    this.restClient =
        RestClient.builder()
            .baseUrl(API_URL)
            .defaultHeader("api-key", properties.getApiKey())
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  @Override
  public void send(String toEmail, String toName, String subject, String htmlContent) {
    Map<String, Object> body =
        Map.of(
            "sender",
                Map.of("email", properties.getSenderEmail(), "name", properties.getSenderName()),
            "to",
                List.of(Map.of("email", toEmail, "name", toName == null ? toEmail : toName)),
            "subject",
            subject,
            "htmlContent",
            htmlContent);

    restClient.post().body(body).retrieve().toBodilessEntity();
    LOG.info("Correo enviado vía Brevo a {}", toEmail);
  }
}
