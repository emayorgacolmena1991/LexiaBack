package com.lexia.api.modules.expedientes.coactivas.ia;

import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import java.util.List;

/**
 * Resultado normalizado de la validación documental por IA.
 *
 * @param estado {@link CoactivaArchivo#IA_APROBADO}, {@link CoactivaArchivo#IA_RECHAZADO} o
 *     {@link CoactivaArchivo#IA_ERROR}
 * @param confianza 0..100 (null si la IA no la informó)
 * @param razonRechazo motivo legible para el abogado (null si aprobado)
 * @param checklistCumplido ítems verificados por la IA
 */
public record CoactivaIaResultado(
    String estado, Integer confianza, String razonRechazo, List<String> checklistCumplido) {

  public static CoactivaIaResultado aprobado(Integer confianza, List<String> checklist) {
    return new CoactivaIaResultado(
        CoactivaArchivo.IA_APROBADO, confianza, null, checklist == null ? List.of() : checklist);
  }

  public static CoactivaIaResultado rechazado(
      Integer confianza, String razon, List<String> checklist) {
    return new CoactivaIaResultado(
        CoactivaArchivo.IA_RECHAZADO, confianza, razon, checklist == null ? List.of() : checklist);
  }

  public static CoactivaIaResultado error(String motivo) {
    return new CoactivaIaResultado(CoactivaArchivo.IA_ERROR, null, motivo, List.of());
  }

  public boolean esError() {
    return CoactivaArchivo.IA_ERROR.equals(estado);
  }
}
