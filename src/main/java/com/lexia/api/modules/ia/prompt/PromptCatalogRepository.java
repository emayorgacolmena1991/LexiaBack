package com.lexia.api.modules.ia.prompt;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptCatalogRepository extends JpaRepository<PromptCatalog, Long> {

  Optional<PromptCatalog> findByCodigo(String codigo);
}
