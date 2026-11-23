package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Quien ve la bandeja completa y quien solo los expedientes de los que es responsable.
 * expedientes:caso:leer abre el modulo. expedientes:caso:leer_todos
 * es el perfil que puede ver los expedientes de todo el tenant.
 */
public final class CaseReadScope {

  public static final String READ_ALL = "expedientes:caso:leer_todos";

  private CaseReadScope() {}

  public static boolean canReadAll(AuthorizationService authorization) {
    return authorization.hasPermission(READ_ALL);
  }

  public static void assertCanRead(AuthorizationService authorization, UUID responsibleMembershipId) {
    if (canReadAll(authorization)) {
      return;
    }
    UUID mine = AuthContext.require().membershipId();
    if (mine == null || !mine.equals(responsibleMembershipId)) {
      throw new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado.");
    }
  }
}