package com.lexia.api.modules.notary;

import com.lexia.api.modules.auth.AuthContext;
import java.util.UUID;
import java.util.function.Predicate;

/** Búsqueda de remisiones que responde 404 a quien no debe saber que existen. */
final class RemisionesVisibles {

  static final String SIN_ORGANIZACION = "Sesión sin organización activa.";

  private RemisionesVisibles() {}

  static NotaryRemision visible(
      NotaryRemisionRepository remisiones, UUID id, Predicate<NotaryRemision> visible) {
    return remisiones
        .findById(id)
        .filter(visible)
        .orElseThrow(() -> NotaryBusinessException.notFound("Remisión no encontrada."));
  }

  /** Tenant activo de la sesión; sin él no hay a quién mostrarle la remisión: 403. */
  static UUID tenantDeSesion() {
    UUID tenantId = AuthContext.require().tenantId();
    if (tenantId == null) {
      throw NotaryBusinessException.forbidden(SIN_ORGANIZACION);
    }
    return tenantId;
  }

  /** Solo la notaría destino: para cualquier otro tenant, incluido el bufete, no existe. */
  static NotaryRemision deLaNotaria(NotaryRemisionRepository remisiones, UUID id) {
    UUID tenantId = tenantDeSesion();
    return visible(remisiones, id, remision -> tenantId.equals(remision.getNotaryTenantId()));
  }
}
