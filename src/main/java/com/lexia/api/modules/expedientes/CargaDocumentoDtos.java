package com.lexia.api.modules.expedientes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CargaDocumentoDtos {

  private CargaDocumentoDtos() {}

  public record CrearBorradorRequest(
      @Size(max = 64) String idActo,
      @Size(max = 64) String productCode,
      @Size(max = 64) String canton) {}

  public record BorradorResponse(
      String idExpediente, String idActo, String estado, String productCode) {}

  public record TipoPermitidoDTO(String codigo, String nombre) {}

  public record DocumentoCargadoDTO(
      String idDocumento, String nombreOriginal, String tamano, String codigoTipoDocumento) {}

  public record ActualizarTipoRequest(@NotBlank @Size(max = 64) String codigoTipoDocumento) {}

  public record TipoActualizadoDTO(String idDocumento, String codigoTipoDocumento, String estado) {}

  public record IniciarProcesamientoResponse(
      String idExpediente, String estado, int siguientePaso, String mensaje) {}

  public record ListaDocumentosResponse(List<DocumentoCargadoDTO> documentos) {}

  /** GET /{id}/prevalidacion */
  public record PrevalidacionDocumentoDTO(
      String idDocumento,
      String nombreOriginal,
      String tipoDocumento,
      String estado,
      String motivo) {}

  public record PrevalidacionDTO(
      String idExpediente,
      String estado,
      int confianza,
      int legibles,
      int total,
      List<PrevalidacionDocumentoDTO> documentos) {}

  /** GET /{id}/ocr-resultados — texto Azure + análisis Gemini por documento. */
  public record DocumentoOcrResultadoDTO(
      String idDocumento,
      String nombreOriginal,
      String tipoDocumento,
      String textoOcr,
      String analisisJson,
      String estado,
      String motivo,
      Integer confianza) {}
}
