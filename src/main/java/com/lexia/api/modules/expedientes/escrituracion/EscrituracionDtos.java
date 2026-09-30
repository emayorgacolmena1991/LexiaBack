package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore.DocumentoExtraidoDTO;
import java.util.List;
import java.util.UUID;

public final class EscrituracionDtos {

  private EscrituracionDtos() {}

  public record ProductoItem(String code, String label, int sortOrder, int requisitosCount) {}

  public record DocumentoRequisitoItem(
      String documentTypeCode,
      String description,
      boolean mandatory,
      int maxValidityDays,
      String canton,
      int sortOrder) {}

  public record PlantillaItem(
      String templateKind, String label, boolean companySuppliesCv, String storageKey) {}

  public record ProductoDetalle(
      String code,
      String label,
      List<DocumentoRequisitoItem> requisitos,
      List<PlantillaItem> plantillas) {}

  public record ConfigurarProductoRequest(
      String productCode, String canton, String ingestionMode, String operationTypeCode) {}

  public record EstudioTituloRequest(String status, String summary, List<String> observaciones) {}

  public record EstudioTituloResponse(
      java.util.UUID studyId, String status, String summary, List<String> openObservations) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record CrearMinutaRequest(String templateKind, String sessionId) {}

  /**
   * @param camposPendientes datos que quedaron sin valor en el documento generado (etiqueta legible
   *     + tag); requieren ingreso manual.
   */
  public record MinutaItem(
      java.util.UUID id,
      String templateKind,
      String productCode,
      String status,
      boolean downloadable,
      List<String> camposPendientes) {

    public MinutaItem {
      camposPendientes = camposPendientes == null ? List.of() : List.copyOf(camposPendientes);
    }

    public MinutaItem(
        java.util.UUID id,
        String templateKind,
        String productCode,
        String status,
        boolean downloadable) {
      this(id, templateKind, productCode, status, downloadable, List.of());
    }
  }

  public record DatosBiessMinutaResponse(MinutaItem minuta, DatosBiessMinuta datos) {}

  /**
   * Dónde retomar el wizard si el usuario sale del flujo.
   * etapaIndex = orden del proceso (e2 estudio = 2, e4 minuta = 4).
   * wizardStep = paso del wizard FE (1–5).
   */
  public record EstadoEscrituracion(
      UUID id,
      String codigo,
      String estado,
      String etapa,
      String etapaCodigo,
      int etapaIndex,
      int wizardStep,
      UUID escrituracionId,
      DatosBiessMinuta datosBiess,
      boolean hasDraft) {}

  /** Estado consolidado en BD para hidratar el FE al abrir o refrescar. */
  public record EstadoMinutaBorrador(
      UUID caseId,
      DatosBiessMinuta datosBiess,
      DatosExtraidos datosExtraidos,
      List<DocumentoExtraidoDTO> documentosExtraidos,
      Dictamen dictamen,
      MinutaViviendaData datosMinuta,
      boolean hasDraft,
      UUID draftId) {}

  public record WritingSnapshot(
      java.util.UUID writingFileId,
      String productCode,
      String canton,
      String ingestionMode,
      EstudioTituloResponse estudio,
      List<MinutaItem> minutas) {}
}
