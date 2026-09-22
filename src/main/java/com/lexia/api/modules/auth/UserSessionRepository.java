package com.lexia.api.modules.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
  List<UserSession> findByUserIdAndRevokedAtIsNull(UUID userId);
}
