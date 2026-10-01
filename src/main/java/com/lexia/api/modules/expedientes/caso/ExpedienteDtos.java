package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ExpedienteDtos {

  private ExpedienteDtos() {}

  public record PromoverBorradorRequest(
      @NotNull UUID draftId,
      @Size(max = 400) String subject,
      @Size(max = 240) String clientName,
      @Size(max = 64) String identification,
      @Size(max = 160) String stage,
      @Size(max = 16) String priority,
      @Size(max = 240) String participants,
      @Size(max = 64) String operationTypeCode) {}

  public record CreateCaseRequest(
      @NotBlank @Size(max = 400) String subject,
      @NotBlank @Size(max = 64) String vertical,
      @Size(max = 8) String caseType,
      @Size(max = 240) String clientName,
      @Size(max = 64) String identification,
      @Size(max = 160) String stage,
      @Size(max = 16) String priority,
      @Size(max = 240) String participants,
      @Size(max = 64) String operationTypeCode,
      @Size(max = 64) String productCode,
      @Size(max = 32) String ingestionMode,
      @Size(max = 64) String canton) {}

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

  /** Bandeja de escrituración. `content` vacío cuando el tenant no tiene expedientes. */
  public record EscrituracionBandejaItem(
      UUID id,
      String codigo,
      String clienteOperacion,
      String etapa,
      String estado,
      String atencion,
      String responsable,
      String iniciales,
      String vencimiento) {}

  public record EscrituracionBandejaPage(
      List<EscrituracionBandejaItem> content,
      long totalElements,
      int totalPages,
      int number,
      int size) {

    public EscrituracionBandejaPage {
      content = content == null ? List.of() : List.copyOf(content);
    }

    public static EscrituracionBandejaPage empty(int page, int size) {
      int safeSize = size <= 0 ? 20 : size;
      return new EscrituracionBandejaPage(List.of(), 0, 0, Math.max(page, 0), safeSize);
    }
  }

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
      String updatedAtLabel,
      UUID escrituracionId,
      DatosBiessMinuta datosBiess,
      boolean hasDraft,
      int etapaIndex,
      int wizardStep) {}

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

  public record ValidacionItem(
      UUID id, String label, String kind, String result, String evidence, Instant createdAt) {}

  public record ExcepcionItem(
      UUID id,
      String title,
      String severity,
      String status,
      String detail,
      String evidence,
      Instant createdAt) {}

  public record ActuacionItem(
      UUID id, String titulo, String tipo, String estado, String actor, Instant fecha) {}

  public record AuditoriaItem(UUID id, String evento, String actor, String resultado, Instant fecha) {}
}
