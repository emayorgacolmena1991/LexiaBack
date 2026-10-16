package com.lexia.api.modules.expedientes.caso;

import java.util.Locale;

/** Motivo estructurado de un 409 en {@code POST /stages/advance}. */
public enum MotivoRechazoAdvance {
  OBSERVACIONES_ABIERTAS,
  ESTADO_INVALIDO,
  CAMPOS_FALTANTES,
  MISMO_ESTADO;

  /** Clasifica el texto que ya producía {@code blockReason}. */
  public static MotivoRechazoAdvance desdeDetalle(String detalle) {
    String texto = detalle == null ? "" : detalle.toLowerCase(Locale.ROOT);
    if (texto.contains("validacion") || texto.contains("observ")) {
      return OBSERVACIONES_ABIERTAS;
    }
    if (texto.contains("gate") || texto.contains("cumple") || texto.contains("campo")) {
      return CAMPOS_FALTANTES;
    }
    return ESTADO_INVALIDO;
  }
}
