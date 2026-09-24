package com.lexia.api.modules.ia.cotejo;

import java.util.List;

/** Respuesta del cotejo de campos entre documentos de una sesión OCR. */
public final class CotejoDtos {

  private CotejoDtos() {}

  public record CotejoResponse(
      String sessionId, CotejoResumen resumen, List<CotejoComparacion> comparaciones) {}

  public record CotejoResumen(
      int total,
      int coinciden,
      int diferencias,
      int noEncontrados,
      int reglasAplicadas,
      boolean observacion) {}

  public record CotejoComparacion(
      String campo,
      String label,
      String estado,
      String relacion,
      String valor,
      List<CotejoFuente> fuentes) {}

  public record CotejoFuente(String documento, String valor) {}
}
