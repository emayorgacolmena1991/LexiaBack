package com.lexia.api.modules.ia.prompt;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Prompts estáticos desde {@code lexia.prompts} (application.yml). Solo lectura; sin overrides
 * runtime (TICKET-DEV-705).
 */
@ConfigurationProperties(prefix = "lexia")
public class PromptRegistryService {

  /** Populated from application.yml {@code lexia.prompts}. */
  private Map<String, String> prompts = new HashMap<>();

  public Map<String, String> getPrompts() {
    return prompts;
  }

  public void setPrompts(Map<String, String> prompts) {
    this.prompts = prompts == null ? new HashMap<>() : new HashMap<>(prompts);
  }

  /**
   * Lee prompt del YML y sustituye variables de contexto ({@code ${canton}}, etc.).
   *
   * @throws IllegalStateException si la key no existe en application.yml
   */
  public String resolvePrompt(String promptKey, Map<String, String> variables) {
    String key = promptKey == null ? "" : promptKey.trim();
    String rawPrompt = prompts.get(key);
    if (!StringUtils.hasText(rawPrompt)) {
      throw new IllegalStateException(
          "Prompt key " + key + " no existe en application.yml");
    }

    if (variables != null) {
      for (Map.Entry<String, String> entry : variables.entrySet()) {
        String value = entry.getValue() == null ? "" : entry.getValue();
        rawPrompt = rawPrompt.replace("${" + entry.getKey() + "}", value);
      }
    }
    return rawPrompt;
  }

  /** Texto template sin sustituir variables. */
  public String getRawPrompt(String promptKey) {
    return resolvePrompt(promptKey, Map.of());
  }

  public Map<String, String> getAllActivePrompts() {
    return Collections.unmodifiableMap(new HashMap<>(prompts));
  }
}
