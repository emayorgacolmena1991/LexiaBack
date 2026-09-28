package com.lexia.api.modules.ia.prompt;

import com.lexia.api.modules.ia.prompt.ProductPromptDtos.ProductPromptDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET prompt por producto BIESS (solo lectura YML; TICKET-DEV-705). */
@RestController
@RequestMapping("/api/v1/productos-biess")
public class ProductPromptController {

  private final ProductPromptService productPromptService;

  public ProductPromptController(ProductPromptService productPromptService) {
    this.productPromptService = productPromptService;
  }

  @GetMapping("/{code}/prompt")
  public ResponseEntity<ProductPromptDTO> getPromptByProduct(@PathVariable String code) {
    return ResponseEntity.ok(productPromptService.getPromptByProductCode(code));
  }
}
