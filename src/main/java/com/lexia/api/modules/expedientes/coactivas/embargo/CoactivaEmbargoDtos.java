package com.lexia.api.modules.expedientes.coactivas.embargo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CoactivaEmbargoDtos {

  private CoactivaEmbargoDtos() {}

  /** Las 11 columnas de la hoja {@code UEC MANTA} de {@code DATA 2.xlsx}, en el mismo orden. */
  public record Datos(
      @Size(max = 250) String juzgado,
      @Size(max = 250) String oficinaOrigenCredito,
      @Size(max = 120) String operacion,
      @Size(max = 120) String numeroJuicio,
      @Size(max = 250) String nombreCoactivado,
      @Size(max = 250) String nombreTitularOperacion,
      @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal valorTransferido,
      LocalDate fechaProceso,
      @Size(max = 250) String nombreDerSac,
      @Size(max = 120) String numeroOficioRespuesta,
      @Size(max = 120) String numeroDocumento) {}

  /**
   * @param loteId lote en el que el usuario abrió el formulario; {@code null} si aún no existía
   * @param rowVersion versión leída del registro; {@code null} si el usuario lo veía como nuevo
   */
  public record GuardarRequest(UUID loteId, Long rowVersion, @NotNull @Valid Datos datos) {}

  /** {@code id} es {@code null} mientras nadie haya guardado un registro en el lote nuevo. */
  public record LoteResumen(
      UUID id,
      int numero,
      String estado,
      Instant fechaCorte,
      long totalRegistros,
      boolean cortePendiente,
      UUID delegadoId,
      String delegadoNombre) {}

  public record LoteEntregado(
      UUID id, int numero, Instant fechaCorte, Instant entregadoAt, long totalRegistros) {}

  public record RegistroItem(
      UUID id,
      UUID loteId,
      UUID expedienteId,
      long rowVersion,
      Datos datos,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * @param aplica el expediente está en contexto de embargo (o ya tiene fila en el lote)
   * @param propuesta autollenado para el formulario cuando todavía no hay registro
   * @param montoRetenidoIa solo informativo; nunca se copia a {@code valorTransferido}
   */
  public record ExpedienteResponse(
      LoteResumen lote,
      Instant horaServidor,
      boolean aplica,
      RegistroItem registro,
      Datos propuesta,
      String montoRetenidoIa,
      UUID delegadoId,
      String delegadoNombre) {}

  public record RegistrosResponse(LoteResumen lote, Instant horaServidor, List<RegistroItem> registros) {}

  /** @param lote lote EN_PREPARACION del delegado; {@code null} si no tiene lote activo */
  public record DelegadoResumen(
      UUID delegadoId, String delegadoNombre, LoteResumen lote, long completos, long pendientes) {}
}
