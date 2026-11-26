package com.lexia.api.modules.ia.ocr;

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
public class OcrProveedorAlertasService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrProveedorAlertasService.class);

  private final ObjectProvider<InAppNotificationService> notificaciones;

  public OcrProveedorAlertasService(ObjectProvider<InAppNotificationService> notificaciones) {
    this.notificaciones = notificaciones;
  }

  public void registrarFalloLote(UUID tenantId, UUID actorUserId, String contexto, String resumen) {
    if (tenantId == null) {
      return;
    }
    Counter.builder("lexia.ocr.proveedor.fallos")
        .tag("contexto", contexto == null ? "GENERICO" : contexto)
        .register(Metrics.globalRegistry)
        .increment();
    long bucket = Instant.now().getEpochSecond() / 1800;
    String dedupe = "ocr-provider:" + tenantId + ":" + bucket;
    notificaciones.ifAvailable(
        svc ->
            svc.notifyTenantAdmins(
                tenantId,
                actorUserId,
                "OCR_PROVIDER_DEGRADED",
                Map.of(
                    "contexto", contexto == null ? "OCR" : contexto,
                    "resumen", resumen == null ? "Error en OCR" : resumen),
                "/administracion?seccion=integraciones",
                dedupe));
    LOG.warn("fallo OCR tenant={} contexto={} resumen={}", tenantId, contexto, resumen);
  }
}