package com.lexia.api.modules.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {
  List<RecoveryCode> findByUserIdAndUsedAtIsNull(UUID userId);

  void deleteByUserId(UUID userId);
}
