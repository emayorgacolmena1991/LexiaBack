package com.lexia.api.modules.auth;

import java.util.UUID;

public record AuthPrincipal(UUID userId, UUID sessionId, UUID tenantId, UUID membershipId) {}
