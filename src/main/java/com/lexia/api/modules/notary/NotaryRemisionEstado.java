package com.lexia.api.modules.notary;

/** Alineado a control.notary_remision.estado y al flujo demo de frontend. */
public enum NotaryRemisionEstado {
  ENVIADO_A_NOTARIA,
  RECIBIDO,
  EN_TRAMITE,
  NOTARIA_COMPLETADA,
  DEVUELTO_AL_BUFETE;

  public static NotaryRemisionEstado fromDb(String value) {
    return NotaryRemisionEstado.valueOf(value.trim().toUpperCase());
  }
}
