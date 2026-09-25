package com.lexia.api.modules.ia.prompt;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.ia.prompt.ProductPromptDtos.ProductPromptDTO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Binding producto BIESS ↔ prompt_key YML (TICKET-DEV-705). */
@Service
public class ProductPromptService {

  private static final String DEFAULT_PROMPT_KEY = "PROMPT_DEFAULT";

  private final ProductPromptMapRepository productPromptMapRepository;
  private final PromptRegistryService promptRegistryService;

  public ProductPromptService(
      ProductPromptMapRepository productPromptMapRepository,
      PromptRegistryService promptRegistryService) {
    this.productPromptMapRepository = productPromptMapRepository;
    this.promptRegistryService = promptRegistryService;
  }

  public ProductPromptDTO getPromptByProductCode(String productCode) {
    String code = requireCode(productCode);
    String promptKey =
        productPromptMapRepository
            .findPromptKeyByProductCode(code)
            .filter(StringUtils::hasText)
            .orElse(DEFAULT_PROMPT_KEY);
    String promptText = promptRegistryService.getRawPrompt(promptKey);
    return new ProductPromptDTO(code, promptKey, promptText);
  }

  private static String requireCode(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      throw ApiException.badRequest("Código de producto requerido.");
    }
    return productCode.trim();
  }
}
