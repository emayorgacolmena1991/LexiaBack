package com.lexia.api.modules.ia.prompt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Resuelve templates de prompt: override runtime → YML ({@code lexia.prompts}) → {@code
 * app.prompt_catalog}.
 */
@ConfigurationProperties(prefix = "lexia")
public class PromptRegistryService {

  private static final String DEFAULT_MISSING = "Prompt por defecto no encontrado.";

  /** Populated from application.yml {@code lexia.prompts}. */
  private Map<String, String> prompts = new ConcurrentHashMap<>();

  private final Map<String, String> runtimeOverrides = new ConcurrentHashMap<>();
  private final PromptCatalogRepository promptCatalogRepository;

  public PromptRegistryService(PromptCatalogRepository promptCatalogRepository) {
    this.promptCatalogRepository = promptCatalogRepository;
  }

  public Map<String, String> getPrompts() {
    return prompts;
  }

  public void setPrompts(Map<String, String> prompts) {
    this.prompts = prompts == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(prompts);
  }

  /**
   * Resuelve template por key, priorizando overrides de API sobre YML y catálogo BD.
   */
  public String resolvePrompt(String promptKey, Map<String, String> variables) {
    String key = promptKey == null ? "" : promptKey.trim();
    String rawPrompt = runtimeOverrides.get(key);
    if (!StringUtils.hasText(rawPrompt)) {
      rawPrompt = prompts.get(key);
    }
    if (!StringUtils.hasText(rawPrompt) && StringUtils.hasText(key)) {
      rawPrompt =
          promptCatalogRepository
              .findByCodigo(key)
              .map(PromptCatalog::getPromptText)
              .filter(StringUtils::hasText)
              .orElse(null);
    }
    if (!StringUtils.hasText(rawPrompt)) {
      rawPrompt = DEFAULT_MISSING;
    }

    if (variables != null) {
      for (Map.Entry<String, String> entry : variables.entrySet()) {
        String value = entry.getValue() == null ? "" : entry.getValue();
        rawPrompt = rawPrompt.replace("${" + entry.getKey() + "}", value);
      }
    }
    return rawPrompt;
  }

  public void updatePromptRuntime(String promptKey, String newPromptText) {
    if (!StringUtils.hasText(promptKey)) {
      return;
    }
    runtimeOverrides.put(promptKey.trim(), newPromptText == null ? "" : newPromptText);
  }

  public Map<String, String> getAllActivePrompts() {
    Map<String, String> combined = new ConcurrentHashMap<>(prompts);
    for (PromptCatalog row : promptCatalogRepository.findAll()) {
      if (StringUtils.hasText(row.getCodigo()) && StringUtils.hasText(row.getPromptText())) {
        combined.putIfAbsent(row.getCodigo(), row.getPromptText());
      }
    }
    combined.putAll(runtimeOverrides);
    return combined;
  }
}
