package com.lexia.api.modules.auth;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {
  List<LoginAttempt> findTop20ByEmailOrderByCreatedAtDesc(String email);

  long countByIpAddressAndCreatedAtAfterAndSuccessFalse(String ipAddress, Instant after);
}
