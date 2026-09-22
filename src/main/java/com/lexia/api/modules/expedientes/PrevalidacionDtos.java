package com.lexia.api.modules.expedientes;

import java.util.List;

public final class PrevalidacionDtos {

  private PrevalidacionDtos() {}

  public static final String EN_PROCESO = "EN_PROCESO";
  public static final String COMPLETADA = "COMPLETADA";
  public static final String ERROR = "ERROR";
  public static final String LEGIBLE = "LEGIBLE";
  public static final String REVISAR = "REVISAR";

  public record PrevalidacionDto(
      String idExpediente,
      String estado,
      double confianza,
      int legibles,
      int total,
      List<PrevalidacionDocumentoDto> documentos) {}

  public record PrevalidacionDocumentoDto(
      String idDocumento,
      String nombreOriginal,
      String tipoDocumento,
      String estado,
      String motivo) {}
}
