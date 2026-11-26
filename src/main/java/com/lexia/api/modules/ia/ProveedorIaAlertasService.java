package com.lexia.api.modules.ia;

import com.lexia.api.modules.notifications.InAppNotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ProveedorIaAlertasService {

  private static final Logger LOG = LoggerFactory.getLogger(ProveedorIaAlertasService.class);

  private final ObjectProvider<InAppNotificationService> notificaciones;

  public ProveedorIaAlertasService(ObjectProvider<InAppNotificationService> notificaciones) {
    this.notificaciones = notificaciones;
  }

  public ProveedorIaErrores.Clasificacion registrarFallo(
      UUID tenantId, UUID actorUserId, String contexto, String motivoTecnico, String href) {
    ProveedorIaErrores.Clasificacion c = ProveedorIaErrores.clasificar(motivoTecnico);
    if (c.falloProveedor()) {
      Counter.builder("lexia.ia.proveedor.fallos")
          .tag("codigo", c.codigo())
          .tag("contexto", contexto == null ? "GENERICO" : contexto)
          .register(Metrics.globalRegistry)
          .increment();
    }
    if (c.notificarAdministradores() && tenantId != null) {
      long bucket = Instant.now().getEpochSecond() / 1800;
      String dedupe = "ia-provider:" + tenantId + ":" + c.codigo() + ":" + bucket;
      notificaciones.ifAvailable(
          svc ->
              svc.notifyTenantAdmins(
                  tenantId,
                  actorUserId,
                  "IA_PROVIDER_DEGRADED",
                  Map.of(
                      "codigo", c.codigo(),
                      "contexto", contexto == null ? "Proceso LEXIA" : contexto,
                      "resumen", c.mensajeAdminResumen()),
                  href == null ? "/administracion?seccion=ia" : href,
                  dedupe));
      LOG.warn(
          "fallo proveedor IA tenant={} contexto={} codigo={} resumen={}",
          tenantId,
          contexto,
          c.codigo(),
          c.mensajeAdminResumen());
    }
    return c;
  }
}