package com.lexia.api.modules.ia.prompt;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPromptMapRepository extends JpaRepository<ProductPromptMap, Long> {

  Optional<ProductPromptMap> findByProductCodeAndActiveTrue(String productCode);

  default Optional<String> findPromptKeyByProductCode(String productCode) {
    return findByProductCodeAndActiveTrue(productCode).map(ProductPromptMap::getPromptKey);
  }
}
