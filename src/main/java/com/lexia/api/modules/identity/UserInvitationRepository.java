package com.lexia.api.modules.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInvitationRepository extends JpaRepository<UserInvitation, UUID> {
  Optional<UserInvitation> findByTokenHash(String tokenHash);

  List<UserInvitation> findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID tenantId);

  List<UserInvitation> findByTenantIdAndRevokedAtIsNotNullOrderByRevokedAtDesc(UUID tenantId);

  List<UserInvitation> findByTenantIdAndEmailIgnoreCaseAndAcceptedAtIsNullAndRevokedAtIsNull(
      UUID tenantId, String email);
}
