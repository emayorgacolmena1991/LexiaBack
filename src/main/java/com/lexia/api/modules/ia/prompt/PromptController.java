package com.lexia.api.modules.ia.prompt;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inspección / override en caliente de prompts (TICKET-DEV-703). */
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

  @PutMapping("/{promptKey}")
  public ResponseEntity<Void> updatePrompt(
      @PathVariable String promptKey, @RequestBody Map<String, String> body) {
    String newPrompt = body == null ? null : body.get("promptText");
    promptRegistryService.updatePromptRuntime(promptKey, newPrompt);
    return ResponseEntity.ok().build();
  }
}
