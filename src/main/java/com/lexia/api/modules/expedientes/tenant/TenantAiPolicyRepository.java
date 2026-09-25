package com.lexia.api.modules.expedientes.tenant;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantAiPolicyRepository extends JpaRepository<TenantAiPolicy, UUID> {}
