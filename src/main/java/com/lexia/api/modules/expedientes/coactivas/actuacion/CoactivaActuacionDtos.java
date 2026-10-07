package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoactivaActuacionDtos {

  private CoactivaActuacionDtos() {}

  /**
   * @param generable la plantilla tiene un .docx asociado y puede generar la actuación
   * @param variables etiqueta → valor de la IA o {@code [COMPLETAR: etiqueta]} si no hubo dato
   */
  public record PlantillaItem(
      UUID id,
      String nombre,
      String etapa,
      boolean generable,
      Map<String, String> variables,
      String codigo) {}

  public record VariablesDocumentoResponse(
      UUID draftId,
      String actuacionTipo,
      String nombre,
      String status,
      Map<String, String> variables,
      List<String> variablesPendientes,
      Map<String, String> origenes,
      Map<String, String> valoresExtraidos,
      Map<String, String> etiquetas,
      boolean liquidacionVigente,
      String downloadUrl) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PreviewDocumentoRequest(Map<String, String> variables, List<String> restaurar) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PublicarDocumentoRequest(Boolean permitirIncompleto) {}

  /** {@code permitirIncompleto} solo se envía tras la confirmación explícita del usuario. */
  public record GenerarActuacionRequest(@NotNull UUID plantillaId, Boolean permitirIncompleto) {}

  /** {@code formato} {@code pdf} (default) o {@code docx}. {@code variables} pisa lo que llenó la IA. */
  public record GenerarDocumentoRequest(
      @NotNull UUID plantillaId, Map<String, String> variables, @Size(max = 8) String formato) {}

  public record ActuacionResponse(UUID actuacionId, UUID archivoId, String estado) {}

  public record ErrorBody(String code, String message) {}

  public record MedidaItem(
      UUID id, String tipo, String descripcion, LocalDate fecha, String estado, Instant createdAt) {}

  public record MedidaRequest(
      @NotBlank @Size(max = 40) String tipo,
      @Size(max = 2000) String descripcion,
      LocalDate fecha) {}

  public record HonorariosResponse(
      boolean disponible,
      String motivo,
      BigDecimal montoOriginal,
      BigDecimal montoRetenido,
      BigDecimal porcentaje,
      BigDecimal honorarios) {}

  public record SolicitudResponse(UUID id, String estado) {}

  public record ResultadoHttp(int http, String code, String message, UUID id) {}
}
