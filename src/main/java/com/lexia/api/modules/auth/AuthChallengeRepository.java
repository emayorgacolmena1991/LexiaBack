package com.lexia.api.modules.auth;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthChallengeRepository extends JpaRepository<AuthChallenge, UUID> {}
