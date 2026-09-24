package com.lexia.api.modules.expedientes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class ExpedienteDtos {

  private ExpedienteDtos() {}

  public record CreateCaseRequest(
      @NotBlank @Size(max = 400) String subject,
      @NotBlank @Size(max = 64) String vertical,
      @Size(max = 8) String caseType,
      @Size(max = 240) String clientName,
      @Size(max = 64) String identification,
      @Size(max = 160) String stage,
      @Size(max = 16) String priority,
      @Size(max = 240) String participants,
      @Size(max = 64) String operationTypeCode) {}

  public record StageAdvanceRequest(
      @Size(max = 16) String targetStageCode, @Size(max = 500) String comment) {}

  public record StageAdvanceResult(
      String fromStageLabel, String toStageLabel, String toStageCode, String message) {}

  public record StageRevertRequest(
      @Size(max = 16) String targetStageCode, @NotBlank @Size(min = 10, max = 500) String comment) {}

  public record StageRevertResult(
      String fromStageLabel, String toStageLabel, String toStageCode, String message) {}

  public record ResolveGateRequest(@NotBlank @Size(max = 16) String result, @Size(max = 500) String comment) {}

  public record ResolveExceptionRequest(@NotBlank @Size(min = 10, max = 2000) String resolution) {}

  public record ConnectorInvokeResult(
      String connectorCode, String callStatus, UUID integrationCallId, String message) {}

  public record CaseSummaryItem(
      UUID id,
      String code,
      String vertical,
      String subject,
      String statusLabel,
      String responsibleName,
      String responsibleInitials,
      String priorityLabel,
      String slaLabel,
      String createdAtLabel,
      String updatedAtLabel,
      String stageLabel,
      boolean demo) {}

  public record CaseDetailItem(
      UUID id,
      String code,
      String vertical,
      String subject,
      String caseType,
      String statusLabel,
      String statusCode,
      String responsibleName,
      String responsibleInitials,
      String priorityLabel,
      String priorityCode,
      String slaLabel,
      String clientName,
      String identification,
      String stage,
      String createdAtLabel,
      String updatedAtLabel) {}

  public record ExpedienteExtraidoDTO(
      List<ArchivoEstadoDTO> archivosProcesados, DatosExtraidosDTO datosExtraidos) {}

  public record ArchivoEstadoDTO(String nombre, String estado) {}

  /**
   * Extracción dinámica Gemini: tipo + resumen + mapa libre de campos clave.
   * Jackson deserializa {@code datosClave} como objeto JSON → Map.
   */
  public record DatosExtraidosDTO(
      String tipoDocumento, String resumen, java.util.Map<String, Object> datosClave) {

    public static DatosExtraidosDTO empty() {
      return new DatosExtraidosDTO("", "", java.util.Map.of());
    }

    public java.util.Map<String, Object> datosClave() {
      return datosClave == null ? java.util.Map.of() : datosClave;
    }
  }

  /**
   * Salida tool use Claude: cotejo notarial cross-documento (Cédula↔Papeleta,
   * Avalúo↔Historia de Dominio).
   */
  public record ResultadoCotejoDTO(
      boolean coincidePersona,
      boolean coincideInmueble,
      java.util.List<String> observaciones,
      String resumenValidacion,
      String estado) {

    public java.util.List<String> observaciones() {
      return observaciones == null ? java.util.List.of() : observaciones;
    }

    public static ResultadoCotejoDTO error(String motivo) {
      return new ResultadoCotejoDTO(
          false, false, java.util.List.of(motivo == null ? "Error de cotejo." : motivo),
          motivo == null ? "No se pudo validar el expediente." : motivo, "RECHAZADO");
    }
  }
}
