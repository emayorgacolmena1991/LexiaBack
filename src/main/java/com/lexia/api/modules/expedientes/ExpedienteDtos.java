package com.lexia.api.modules.expedientes;

import java.util.List;

public final class ExpedienteDtos {

  private ExpedienteDtos() {}

  public record ExpedienteExtraidoDTO(
      List<ArchivoEstadoDTO> archivosProcesados, DatosExtraidosDTO datosExtraidos) {}

  public record ArchivoEstadoDTO(String nombre, String estado) {}

  public record DatosExtraidosDTO(
      String tipoDocumento,
      String numeroEscritura,
      String fechaEscritura,
      String notaria,
      String municipio,
      String comparecientes,
      String nitIdentificacion,
      String objetoAsunto) {

    static DatosExtraidosDTO empty() {
      return new DatosExtraidosDTO(null, null, null, null, null, null, null, null);
    }
  }
}
