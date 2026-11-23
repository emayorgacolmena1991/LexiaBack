package com.lexia.api.modules.notary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotaryTenantProfileRepository extends JpaRepository<NotaryTenantProfile, UUID> {

  Optional<NotaryTenantProfile> findByRuc(String ruc);

  @Query(
      """
      SELECT p FROM NotaryTenantProfile p
      WHERE p.listed = true
        AND p.tenantId IN (
          SELECT ps.notaryTenantId FROM TenantNotaryPartnership ps
          WHERE ps.firmTenantId = :firmTenantId AND ps.status = 'ACTIVE'
        )
      ORDER BY p.canton, p.officialName
      """)
  List<NotaryTenantProfile> findListedForFirm(UUID firmTenantId);
}
