package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.identity.AuthorizationService;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Permisos de expedientes para los controladores que antes solo pedían sesión (el filtro de
 * Spring da ROLE_USER a toda sesión). Mismo patrón que CargaDocumentoController:
 * AuthorizationService solo existe con lexia.auth.enabled=true.
 */
public final class PermisoExpediente {

  public static final String LEER = "expedientes:caso:leer";
  public static final String ESCRIBIR = "expedientes:caso:escribir";

  private PermisoExpediente() {}

  public static void leer(ObjectProvider<AuthorizationService> authorization) {
    authorization.ifAvailable(service -> service.requirePermission(LEER));
  }

  public static void escribir(ObjectProvider<AuthorizationService> authorization) {
    authorization.ifAvailable(service -> service.requirePermission(ESCRIBIR));
  }
}
