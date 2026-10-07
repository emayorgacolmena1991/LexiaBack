package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore.DocumentoExtraidoDTO;
import java.time.Instant;
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

  public record ObservacionCotejoItem(
      UUID id,
      String code,
      String severidad,
      String mensaje,
      String estado,
      Instant createdAt) {}

  public record EstudioTituloResponse(
      UUID studyId,
      String status,
      String summary,
      List<String> openObservations,
      List<ObservacionCotejoItem> observaciones) {}

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
   * Párrafo del DOCX guardado de la minuta, en orden de documento (cuerpo y celdas de tablas).
   *
   * @param index posición estable del párrafo; es la clave para editarlo.
   * @param alineacion LEFT, CENTER, RIGHT, BOTH (justificado) o null si hereda del estilo.
   * @param negrita todo el texto visible del párrafo está en negrita.
   */
  public record ParrafoMinuta(
      int index,
      String texto,
      String estilo,
      String alineacion,
      boolean negrita,
      boolean titulo,
      boolean enTabla) {}

  public record ContenidoMinutaResponse(
      MinutaItem minuta, boolean editadoManualmente, List<ParrafoMinuta> parrafos) {}

  public record ParrafoEditado(int index, String texto) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record GuardarContenidoMinutaRequest(List<ParrafoEditado> parrafos) {}

  /**
   * "Guardar" del editor: párrafos editados + datos del panel "Datos manuales desde BIESS". Ambos
   * opcionales; con {@code datosBiess == null} no se tocan los campos del crédito.
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record GuardarMinutaRequest(List<ParrafoEditado> parrafos, DatosBiessMinuta datosBiess) {}

  /**
   * Resultado de "Generar borrador": DOCX renderizado desde la plantilla (sin LLM sobre el texto
   * legal) ya convertido a párrafos para el editor web.
   *
   * @param status estado del borrador ({@code DRAFT_GENERATED} cuando el archivo existe).
   * @param editorContent párrafos del DOCX guardado; es lo único que debe cargar el editor.
   * @param downloadUrl ruta relativa del binario; sirve el mismo archivo que {@code editorContent}.
   */
  public record BorradorGeneradoResponse(
      UUID minutaId,
      String status,
      MinutaItem minuta,
      boolean editadoManualmente,
      List<ParrafoMinuta> editorContent,
      DatosBiessMinuta datosBiess,
      String downloadUrl) {}

  public record MinutaGuardadaResponse(
      MinutaItem minuta,
      boolean editadoManualmente,
      List<ParrafoMinuta> parrafos,
      DatosBiessMinuta datosBiess,
      String downloadUrl) {}

  /**
   * TICKET-INT-102 [C.1]: JSON unificado de la minuta (LLM + BIESS + overrides manuales).
   *
   * @param minutaId borrador al que pertenecen; {@code null} si aún no se generó ninguno.
   * @param variables tag canónico → valor ("" si falta) de todas las variables del acto.
   * @param variablesPendientes tags que la plantilla usa y siguen sin valor ({@code nodata}).
   * @param etiquetas tag → etiqueta legible para el formulario.
   */
  public record VariablesMinutaResponse(
      UUID minutaId,
      String templateKind,
      String productCode,
      String status,
      boolean editadoManualmente,
      java.util.Map<String, String> variables,
      List<String> variablesPendientes,
      java.util.Map<String, String> etiquetas,
      boolean completo,
      String downloadUrl,
      java.util.Map<String, String> origenes,
      java.util.Map<String, ValorExtraido> valoresExtraidos,
      DatosBiessMinuta datosBiess,
      java.util.Map<String, String> camposBiess) {}

  /**
   * Valor al que vuelve "Restaurar" un campo corregido a mano.
   *
   * @param origen IA | BIESS, o {@code null} si no hay valor extraído
   */
  public record ValorExtraido(String valor, String origen) {}

  /**
   * TICKET-INT-102 [C.2]: variables corregidas/llenadas en el panel (solo las que cambian) y tags
   * cuyo override manual se elimina para volver al valor extraído (IA o BIESS).
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record PreviewMinutaRequest(
      java.util.Map<String, Object> variables, List<String> restaurar) {}

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
      boolean hasDraft,
      String estadoRegularizacion,
      boolean flagBloqueoReenvio) {}

  public record RegularizacionResponse(
      UUID expedienteId,
      UUID escrituracionId,
      String estadoRegularizacion,
      boolean flagBloqueoReenvio,
      boolean puedeMarcarRegularizado,
      List<ObservacionCotejoItem> observacionesAbiertas) {}

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
