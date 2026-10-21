package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.common.api.ApiException;

/** El lote no confirma si el body no trae el documento y la razón que vio la abogada. */
public final class CoactivaLoteEvidencia {

  private CoactivaLoteEvidencia() {}

  public static void exigir(String documento, String justificacion) {
    if (documento == null || documento.isBlank() || justificacion == null || justificacion.isBlank()) {
      throw ApiException.badRequest("Cada expediente del lote debe incluir documento y razón.");
    }
  }
}
