package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateDefRepository extends JpaRepository<TemplateDef, UUID> {

  List<TemplateDef> findByTenantIdAndVerticalOrderByCodeAscVersionDesc(
      UUID tenantId, String vertical);
}
