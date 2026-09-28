package com.lexia.api.modules.ia.prompt;

public final class ProductPromptDtos {

  private ProductPromptDtos() {}

  public record ProductPromptDTO(String productCode, String promptKey, String promptText) {}
}
