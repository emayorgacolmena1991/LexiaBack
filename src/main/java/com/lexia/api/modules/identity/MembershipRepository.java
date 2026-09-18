package com.lexia.api.modules.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
  List<Membership> findByTenantId(UUID tenantId);

  List<Membership> findByUserIdAndStatus(UUID userId, String status);

  Optional<Membership> findFirstByUserIdAndTenantIdAndDeletedAtIsNull(UUID userId, UUID tenantId);

  @Query(value = "SELECT * FROM app.active_memberships_for_user(:userId)", nativeQuery = true)
  List<Membership> findActiveForLogin(@Param("userId") UUID userId);
}
