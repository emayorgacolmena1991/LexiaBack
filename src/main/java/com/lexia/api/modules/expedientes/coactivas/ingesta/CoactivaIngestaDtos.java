package com.lexia.api.modules.expedientes.coactivas.ingesta;

import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ArchivoItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoactivaIngestaDtos {

  private CoactivaIngestaDtos() {}

  public record ActaResumen(
      UUID id,
      String tipo,
      String titulo,
      String oficinaCodigo,
      LocalDate fechaActa,
      LocalDate fechaRecepcion,
      int totalItems,
      String estado,
      String fuente,
      UUID archivoId,
      Instant createdAt,
      Instant confirmadaAt) {}

  public record ActaItemDto(
      UUID id,
      int fila,
      String oficinaCodigo,
      String nroOperacion,
      String nroJuicio,
      Integer anio,
      String deudorNombre,
      String deudorCedula,
      boolean cedulaValida,
      String etapaReportada,
      String etapaDetectada,
      Integer fojas,
      UUID expedienteId,
      String estadoMatch,
      String errores) {}

  public record ActaDetalle(
      ActaResumen acta,
      String entregadoPor,
      String recibidoPor,
      String observaciones,
      List<ActaItemDto> items,
      Map<String, Long> conteos,
      List<String> advertencias) {}

  public record ActaItemRequest(
      UUID id,
      @Size(max = 32) String oficinaCodigo,
      @Size(max = 40) String nroOperacion,
      @Size(max = 40) String nroJuicio,
      @Size(max = 240) String deudorNombre,
      @Size(max = 20) String deudorCedula,
      @Size(max = 160) String etapaReportada,
      Integer fojas,
      Boolean omitir) {}

  public record ActaRequest(
      @Size(max = 16) String tipo,
      @Size(max = 240) String titulo,
      @Size(max = 32) String oficinaCodigo,
      LocalDate fechaActa,
      LocalDate fechaRecepcion,
      @Size(max = 200) String entregadoPor,
      @Size(max = 200) String recibidoPor,
      String observaciones,
      @Valid List<ActaItemRequest> items,
      List<UUID> eliminarItemIds) {}

  public record ConfirmacionResponse(
      UUID actaId, int creados, int duplicados, int omitidos, ActaDetalle detalle) {}

  public record CargaMasivaResponse(int total, int vinculados, int sinAsignar, List<ArchivoItem> archivos) {}

  public record VincularRequest(@NotNull UUID expedienteId) {}
}
