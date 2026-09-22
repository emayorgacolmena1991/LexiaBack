package com.lexia.api.modules.auth;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class SessionInvalidationService {

  private final UserSessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;

  public SessionInvalidationService(
      UserSessionRepository sessions, RefreshTokenRepository refreshTokens) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
  }

  @Transactional
  public void invalidateUserSessions(UUID userId) {
    sessions.findByUserIdAndRevokedAtIsNull(userId).forEach(
        session -> {
          session.revoke();
          refreshTokens.deleteAll(refreshTokens.findBySessionId(session.getId()));
        });
  }
}
