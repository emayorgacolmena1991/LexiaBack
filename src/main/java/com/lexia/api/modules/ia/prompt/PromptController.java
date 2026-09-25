package com.lexia.api.modules.ia.prompt;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inspección read-only de prompts YML (TICKET-DEV-705). */
@RestController
@RequestMapping("/api/v1/prompts")
public class PromptController {

  private final PromptRegistryService promptRegistryService;

  public PromptController(PromptRegistryService promptRegistryService) {
    this.promptRegistryService = promptRegistryService;
  }

  @GetMapping
  public ResponseEntity<Map<String, String>> listPrompts() {
    return ResponseEntity.ok(promptRegistryService.getAllActivePrompts());
  }
}
