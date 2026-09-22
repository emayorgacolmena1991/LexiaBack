package com.lexia.api.modules.identity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
  List<Membership> findByTenantId(UUID tenantId);

  List<Membership> findByTenantIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
      UUID tenantId, String status);

  List<Membership> findByTenantIdAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
      UUID tenantId, Collection<String> statuses);

  List<Membership> findByUserIdAndStatus(UUID userId, String status);

  Optional<Membership> findFirstByUserIdAndTenantIdAndDeletedAtIsNull(UUID userId, UUID tenantId);

  @Query(value = "SELECT * FROM app.active_memberships_for_user(:userId)", nativeQuery = true)
  List<Membership> findActiveForLogin(@Param("userId") UUID userId);

  long countByTenantIdAndDeletedAtIsNullAndStatusIn(UUID tenantId, Collection<String> statuses);

  @Query(
      value =
          """
          SELECT DISTINCT m.user_id
          FROM app.membership m
          INNER JOIN app.membership_role mr ON mr.membership_id = m.id AND mr.tenant_id = m.tenant_id
          INNER JOIN app.role_permission rp ON rp.role_id = mr.role_id AND rp.tenant_id = m.tenant_id
          INNER JOIN app.permission p ON p.id = rp.permission_id
          WHERE m.tenant_id = :tenantId
            AND m.deleted_at IS NULL
            AND m.status = 'ACTIVE'
            AND p.code IN (
              'admin:tenant:escribir',
              'admin:tenant:leer',
              'admin:usuarios:invitar',
              'admin:integraciones:escribir',
              'auditoria:evento:leer'
            )
          """,
      nativeQuery = true)
  List<UUID> findAdminNotifierUserIds(@Param("tenantId") UUID tenantId);
}
