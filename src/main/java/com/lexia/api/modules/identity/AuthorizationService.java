package com.lexia.api.modules.identity;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AuthorizationService {

  private final AuthorizationRepository authorization;

  public AuthorizationService(AuthorizationRepository authorization) {
    this.authorization = authorization;
  }

  @Transactional(readOnly = true)
  public List<String> permissionsForCurrentMembership() {
    AuthPrincipal principal = AuthContext.require();
    return permissionsForMembership(principal.membershipId());
  }

  @Transactional(readOnly = true)
  public List<String> permissionsForMembership(UUID membershipId) {
    if (membershipId == null) {
      return List.of();
    }
    return authorization.findPermissionCodesByMembershipId(membershipId);
  }

  @Transactional(readOnly = true)
  public void requirePermission(String permissionCode) {
    if (!permissionsForCurrentMembership().contains(permissionCode)) {
      throw new AuthException(
          HttpStatus.FORBIDDEN, "FORBIDDEN", "No tienes permiso para realizar esta acción.");
    }
  }
}
