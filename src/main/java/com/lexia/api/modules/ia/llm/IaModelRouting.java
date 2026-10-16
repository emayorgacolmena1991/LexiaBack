package com.lexia.api.modules.ia.llm;

import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;

/** El modelo pesado entra solo si el cotejo liviano dejó incoherencias. */
public final class IaModelRouting {

  private IaModelRouting() {}

  public static boolean necesitaPesado(ExtraccionExpedienteCompleto extraccion) {
    if (extraccion == null || extraccion.payload() == null || !"OK".equals(extraccion.estado())) {
      return true;
    }
    Dictamen dictamen = extraccion.payload().dictamen();
    if (dictamen == null || "APPROVED".equalsIgnoreCase(dictamen.estado())) {
      return false;
    }
    for (Observacion obs : dictamen.observaciones()) {
      String nivel = obs.severidad() == null ? "" : obs.severidad().trim().toUpperCase();
      if (nivel.equals("HIGH") || nivel.equals("ALTA") || nivel.equals("MEDIUM") || nivel.equals("MEDIA")) {
        return true;
      }
    }
    return "REJECTED".equalsIgnoreCase(dictamen.estado());
  }

  /** {@code null} si el cotejo liviano alcanza. */
  public static String motivo(ExtraccionExpedienteCompleto extraccion) {
    if (extraccion == null
        || extraccion.payload() == null
        || extraccion.payload().dictamen() == null
        || !necesitaPesado(extraccion)) {
      return null;
    }
    for (Observacion obs : extraccion.payload().dictamen().observaciones()) {
      String nivel = obs.severidad() == null ? "" : obs.severidad().trim().toUpperCase();
      if (nivel.equals("HIGH") || nivel.equals("ALTA")) {
        return "INCOHERENCIA_ALTA";
      }
    }
    for (Observacion obs : extraccion.payload().dictamen().observaciones()) {
      String nivel = obs.severidad() == null ? "" : obs.severidad().trim().toUpperCase();
      if (nivel.equals("MEDIUM") || nivel.equals("MEDIA")) {
        return "OBSERVACION_MEDIA";
      }
    }
    return "INCOHERENCIA_ALTA";
  }
}
