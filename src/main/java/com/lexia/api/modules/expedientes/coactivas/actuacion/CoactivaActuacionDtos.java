package com.lexia.api.modules.expedientes.coactivas.actuacion;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class CoactivaActuacionDtos {

  private CoactivaActuacionDtos() {}

  /** @param generable la plantilla tiene un .docx asociado y puede generar la actuación */
  public record PlantillaItem(UUID id, String nombre, String etapa, boolean generable) {}

  /** {@code permitirIncompleto} solo se envía tras la confirmación explícita del usuario. */
  public record GenerarActuacionRequest(@NotNull UUID plantillaId, Boolean permitirIncompleto) {}

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
