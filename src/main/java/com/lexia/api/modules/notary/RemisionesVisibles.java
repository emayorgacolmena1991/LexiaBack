package com.lexia.api.modules.notary;

import com.lexia.api.modules.auth.AuthContext;
import java.util.UUID;
import java.util.function.Predicate;

/** Búsqueda de remisiones que responde 404 a quien no debe saber que existen. */
final class RemisionesVisibles {

  private RemisionesVisibles() {}

  static NotaryRemision visible(
      NotaryRemisionRepository remisiones, UUID id, Predicate<NotaryRemision> visible) {
    return remisiones
        .findById(id)
        .filter(visible)
        .orElseThrow(() -> NotaryBusinessException.notFound("Remisión no encontrada."));
  }

  /** Solo la notaría destino: para cualquier otro tenant, incluido el bufete, no existe. */
  static NotaryRemision deLaNotaria(NotaryRemisionRepository remisiones, UUID id) {
    UUID tenantId = AuthContext.require().tenantId();
    return visible(remisiones, id, remision -> tenantId.equals(remision.getNotaryTenantId()));
  }
}
