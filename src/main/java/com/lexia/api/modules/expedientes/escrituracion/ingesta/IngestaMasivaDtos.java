package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class IngestaMasivaDtos {

  private IngestaMasivaDtos() {}

  public record CrearLoteRequest(@NotEmpty @Valid List<FilaRequest> filas) {}

  public record FilaRequest(
      @NotBlank @Size(max = 80) String clientRowId,
      @Size(max = 80) String codigo,
      @NotBlank @Size(max = 64) String productCode,
      @NotBlank @Size(max = 64) String canton,
      @Size(max = 32) String ingestionMode) {}

  public record AsignacionesPayload(@NotEmpty @Valid List<AsignacionArchivo> archivos) {}

  public record AsignacionArchivo(
      @NotBlank @Size(max = 80) String clientRowId,
      @NotBlank @Size(max = 40) String borradorId,
      @Size(max = 64) String tipoDocumento) {}

  public record ArchivoStatus(
      String fileId,
      String nombre,
      String tipoDocumento,
      String semaforo,
      int confianza,
      boolean legible,
      String mensaje,
      String textoParcial) {}

  public record FilaStatus(
      String clientRowId,
      UUID borradorId,
      UUID expedienteId,
      String codigo,
      String productCode,
      String canton,
      String ingestionMode,
      String ocrEstado,
      String semaforo,
      int confianza,
      String observaciones,
      String iaEstado,
      String iaResumen,
      List<ArchivoStatus> archivos) {}

  public record LoteStatus(
      UUID batchId,
      String fase,
      int total,
      int listasOcr,
      int verdes,
      int naranjas,
      int rojos,
      boolean cotejoHabilitado,
      List<FilaStatus> filas) {}
}
