package com.lexia.api.modules.auth;

import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/** Toda revocación o borrado de una sesión (logout, cambio de tenant, admin, contraseña) la saca de la caché. */
public class UserSessionCacheListener {

  @PostUpdate
  @PostRemove
  void invalidar(UserSession session) {
    SessionCache.invalidarGlobal(session.getId());
  }
}
