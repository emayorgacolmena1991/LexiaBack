package com.lexia.api.modules.notary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantNotaryPartnershipRepository
    extends JpaRepository<TenantNotaryPartnership, UUID> {

  Optional<TenantNotaryPartnership> findByFirmTenantIdAndNotaryTenantId(
      UUID firmTenantId, UUID notaryTenantId);

  List<TenantNotaryPartnership> findByFirmTenantIdAndStatus(UUID firmTenantId, String status);

  List<TenantNotaryPartnership> findByNotaryTenantIdAndStatus(UUID notaryTenantId, String status);
}
