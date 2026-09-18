package com.lexia.api.modules.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthorizationRepository extends JpaRepository<Permission, UUID> {

  @Query(
      value =
          """
          SELECT DISTINCT p.code
          FROM app.membership_role mr
          JOIN app.role_permission rp ON rp.role_id = mr.role_id AND rp.tenant_id = mr.tenant_id
          JOIN app.permission p ON p.id = rp.permission_id
          WHERE mr.membership_id = :membershipId
          ORDER BY p.code
          """,
      nativeQuery = true)
  List<String> findPermissionCodesByMembershipId(@Param("membershipId") UUID membershipId);
}
