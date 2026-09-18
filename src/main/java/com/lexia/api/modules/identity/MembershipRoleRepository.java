package com.lexia.api.modules.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRoleRepository extends JpaRepository<MembershipRole, MembershipRole.Pk> {
  List<MembershipRole> findByMembershipId(UUID membershipId);
}
