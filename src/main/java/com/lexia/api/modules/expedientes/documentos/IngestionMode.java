package com.lexia.api.modules.expedientes.documentos;

import java.util.Locale;

/**
 * Modo de ingesta documental del expediente. Valores alineados con el CHECK de
 * {@code ingestion_mode} (V43).
 */
public enum IngestionMode {
  DIGITAL_SEPARADO,
  FISICO_ESCANEADO;

  /** Tipo documental compuesto: un único PDF con el expediente físico completo. */
  public static final String EXPEDIENTE_FISICO_ESCANEADO = "EXPEDIENTE_FISICO_ESCANEADO";

  public static final String EXPEDIENTE_FISICO_ESCANEADO_LABEL = "Expediente físico escaneado";

  /** Tolerante a nulos/valores desconocidos: cae en {@link #DIGITAL_SEPARADO}. */
  public static IngestionMode from(String raw) {
    if (raw == null || raw.isBlank()) {
      return DIGITAL_SEPARADO;
    }
    String mode = raw.trim().toUpperCase(Locale.ROOT);
    if ("FISICO_ESCANEDO".equals(mode)) {
      return FISICO_ESCANEADO;
    }
    try {
      return valueOf(mode);
    } catch (IllegalArgumentException e) {
      return DIGITAL_SEPARADO;
    }
  }

  public boolean isFisicoEscaneado() {
    return this == FISICO_ESCANEADO;
  }
}
