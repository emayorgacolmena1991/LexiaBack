package com.lexia.api.modules.expedientes.documentos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CargaDocumentoDtos {

  private CargaDocumentoDtos() {}

  public record CrearBorradorRequest(
      @Size(max = 64) String idActo,
      @Size(max = 64) String productCode,
      @Size(max = 64) String canton,
      @Size(max = 32) String ingestionMode) {

    public CrearBorradorRequest(String idActo, String productCode, String canton) {
      this(idActo, productCode, canton, null);
    }
  }

  /** `id` e `idExpediente` son el mismo UUID de PostgreSQL (contrato front/back). */
  public record BorradorResponse(
      String id,
      String idExpediente,
      String idActo,
      String estado,
      String productCode,
      String ingestionMode) {

    public BorradorResponse(
        String idExpediente, String idActo, String estado, String productCode, String ingestionMode) {
      this(idExpediente, idExpediente, idActo, estado, productCode, ingestionMode);
    }

    public BorradorResponse(String idExpediente, String idActo, String estado, String productCode) {
      this(idExpediente, idActo, estado, productCode, IngestionMode.DIGITAL_SEPARADO.name());
    }
  }

  public record TipoPermitidoDTO(String codigo, String nombre) {}

  /**
   * Documento cargado (borrador o expediente). {@code tipoSugerido}/{@code clasificacionEstado}/
   * {@code clasificacionOrigen}: resultado del clasificador automático; null si no corrió.
   */
  public record DocumentoCargadoDTO(
      String idDocumento,
      String nombreOriginal,
      String tamano,
      String codigoTipoDocumento,
      String tipoSugerido,
      String clasificacionEstado,
      String clasificacionOrigen,
      String subidoPor,
      java.time.Instant subidoEn,
      String observacion) {

    public DocumentoCargadoDTO(
        String idDocumento, String nombreOriginal, String tamano, String codigoTipoDocumento) {
      this(idDocumento, nombreOriginal, tamano, codigoTipoDocumento, null, null, null);
    }

    public DocumentoCargadoDTO(
        String idDocumento,
        String nombreOriginal,
        String tamano,
        String codigoTipoDocumento,
        String tipoSugerido,
        String clasificacionEstado,
        String clasificacionOrigen) {
      this(
          idDocumento,
          nombreOriginal,
          tamano,
          codigoTipoDocumento,
          tipoSugerido,
          clasificacionEstado,
          clasificacionOrigen,
          null,
          null,
          null);
    }

    /** Mismo valor que {@code codigoTipoDocumento}, con el nombre que usa el resto del API. */
    @com.fasterxml.jackson.annotation.JsonProperty("tipoDocumento")
    public String tipoDocumento() {
      return codigoTipoDocumento;
    }
  }

  /**
   * Respuesta del upload. {@code ocrOk}: resultado del OCR síncrono en un expediente persistido;
   * {@code null} en un borrador, donde el OCR lo dispara el wizard.
   */
  public record DocumentoSubidoDTO(
      String idDocumento,
      String nombreOriginal,
      String tamano,
      String codigoTipoDocumento,
      Boolean ocrOk) {

    public static DocumentoSubidoDTO de(DocumentoCargadoDTO doc, Boolean ocrOk) {
      return new DocumentoSubidoDTO(
          doc.idDocumento(), doc.nombreOriginal(), doc.tamano(), doc.codigoTipoDocumento(), ocrOk);
    }
  }

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
