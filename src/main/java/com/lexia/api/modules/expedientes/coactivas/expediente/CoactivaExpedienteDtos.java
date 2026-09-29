package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.OficinaItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoactivaExpedienteDtos {

  private CoactivaExpedienteDtos() {}

  public record EtapaItem(String code, String stageCode, String label) {}

  public record CatalogosResponse(
      List<OficinaItem> oficinas, List<DelegadoItem> delegados, List<EtapaItem> etapas) {}

  public record ExpedienteResumen(
      UUID id,
      UUID caseId,
      String nroJuicio,
      String nroOperacion,
      Integer anio,
      String oficinaCodigo,
      UUID delegadoId,
      String delegadoNombre,
      String deudorNombre,
      String deudorIdentificacion,
      int participantes,
      String estadoOperativo,
      String etapaReportada,
      String etapaReportadaTexto,
      String etapaVerificada,
      String etapaLabel,
      String semaforo,
      String semaforoMotivo,
      String siguienteAccion,
      long archivos,
      boolean suspendido,
      LocalDate fechaUltimaActuacion,
      Instant updatedAt) {}

  public record BandejaResponse(
      List<ExpedienteResumen> items,
      int page,
      int size,
      long total,
      Map<String, Long> porSemaforo,
      Map<String, Long> porEtapa,
      Map<String, Long> porEstado) {}

  public record ParticipanteItem(
      UUID id,
      String rol,
      int orden,
      String tipoPersona,
      String tipoIdentificacion,
      String identificacion,
      boolean identificacionValida,
      String nombreCompleto,
      List<String> emails,
      String direccion,
      String telefono,
      String fuente,
      BigDecimal confianzaIa,
      boolean verificado,
      boolean notificadoOpi) {}

  public record ParticipanteRequest(
      @NotBlank @Size(max = 16) String rol,
      Integer orden,
      @Size(max = 16) String tipoPersona,
      @Size(max = 16) String tipoIdentificacion,
      @Size(max = 20) String identificacion,
      @NotBlank @Size(max = 240) String nombreCompleto,
      List<String> emails,
      @Size(max = 400) String direccion,
      @Size(max = 40) String telefono,
      Boolean verificado) {}

  public record NotificacionItem(
      UUID id,
      UUID participanteId,
      String participanteNombre,
      String acto,
      String medio,
      Integer numeroBoleta,
      LocalDate fecha,
      UUID archivoId,
      Integer paginaDesde,
      Integer paginaHasta,
      boolean valida,
      String fuente,
      String observacion) {}

  public record NotificacionRequest(
      UUID participanteId,
      @NotBlank @Size(max = 16) String acto,
      @NotBlank @Size(max = 16) String medio,
      Integer numeroBoleta,
      LocalDate fecha,
      UUID archivoId,
      Integer paginaDesde,
      Integer paginaHasta,
      Boolean valida,
      @Size(max = 400) String observacion) {}

  public record ArchivoItem(
      UUID id,
      UUID expedienteId,
      String tipo,
      String nombreOriginal,
      String mimeType,
      long tamanoBytes,
      String nroJuicioDetectado,
      String estadoVinculo,
      Instant createdAt) {}

  public record ExpedienteDetalle(
      UUID id,
      UUID caseId,
      String caseCode,
      String nroJuicio,
      String nroOperacion,
      Integer anio,
      String oficinaCodigo,
      DelegadoItem delegado,
      UUID saeUserId,
      UUID asistenteUserId,
      Integer fojas,
      String estadoOperativo,
      String etapaReportada,
      String etapaReportadaTexto,
      String etapaVerificada,
      String etapaLabel,
      String etapaSugeridaIa,
      Instant etapaConfirmadaAt,
      String semaforo,
      String semaforoMotivo,
      String siguienteAccion,
      LocalDate fechaCitacionOpi,
      BigDecimal montoOriginal,
      boolean convenioUsado,
      boolean suspendido,
      String suspensionMotivo,
      UUID actaEntregaId,
      LocalDate fechaUltimaActuacion,
      String observaciones,
      List<ParticipanteItem> participantes,
      List<NotificacionItem> notificaciones,
      List<String> etapasPermitidas,
      Instant createdAt,
      Instant updatedAt,
      Long rowVersion) {}

  public record CreateExpedienteRequest(
      @NotBlank @Size(max = 40) String nroJuicio,
      @Size(max = 40) String nroOperacion,
      @Size(max = 32) String oficinaCodigo,
      UUID delegadoId,
      @NotBlank @Size(max = 240) String deudorNombre,
      @Size(max = 20) String deudorIdentificacion,
      @Size(max = 160) String etapaReportada,
      Integer fojas,
      BigDecimal montoOriginal,
      String observaciones) {}

  public record UpdateExpedienteRequest(
      @Size(max = 40) String nroOperacion,
      @Size(max = 32) String oficinaCodigo,
      UUID delegadoId,
      Boolean quitarDelegado,
      UUID saeUserId,
      UUID asistenteUserId,
      Integer fojas,
      LocalDate fechaCitacionOpi,
      BigDecimal montoOriginal,
      Boolean convenioUsado,
      Boolean suspendido,
      @Size(max = 400) String suspensionMotivo,
      LocalDate fechaUltimaActuacion,
      String observaciones) {}

  public record CambioEtapaRequest(
      @NotBlank String etapa, @Size(max = 600) String motivo, Boolean forzar) {}

  public record TimelineItem(
      String tipo, String titulo, String detalle, Instant fecha, UUID usuarioId) {}
}
