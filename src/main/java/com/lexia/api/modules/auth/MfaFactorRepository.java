package com.lexia.api.modules.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MfaFactorRepository extends JpaRepository<MfaFactor, UUID> {
  Optional<MfaFactor> findByUserIdAndFactorTypeAndEnabledTrue(UUID userId, String factorType);

  List<MfaFactor> findByUserIdAndFactorType(UUID userId, String factorType);
}
