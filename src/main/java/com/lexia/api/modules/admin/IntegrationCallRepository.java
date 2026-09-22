package com.lexia.api.modules.admin;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationCallRepository extends JpaRepository<IntegrationCall, UUID> {

  Optional<IntegrationCall> findFirstByIntegrationIdAndTenantIdOrderByCreatedAtDesc(
      UUID integrationId, UUID tenantId);

  Optional<IntegrationCall> findByTenantIdAndIntegrationIdAndIdempotencyKey(
      UUID tenantId, UUID integrationId, String idempotencyKey);

  Optional<IntegrationCall> findByIdAndTenantId(UUID id, UUID tenantId);
}
