package com.lexia.api.modules.expedientes.escrituracion.core;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.TipoPermitidoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.DocumentVersion;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.DocumentoRelacionadoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionCotejoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionesDocumentoItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Observaciones abiertas del estudio agrupadas por documento del expediente (pantalla E3).
 * Comparten la consulta la regularización y {@code GET escrituracion/estado}.
 */
@Service
public class ObservacionesPorDocumento {

  private final LegalDocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final CargaDocumentoService carga;

  public ObservacionesPorDocumento(
      LegalDocumentRepository documents,
      DocumentVersionRepository versions,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      CargaDocumentoService carga) {
    this.documents = documents;
    this.versions = versions;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.carga = carga;
  }

  /**
   * Tipos requeridos del producto (catálogo de {@code tiposPermitidos}): se pasan a la IA para
   * {@code tipoDocumentoFaltante} y validan lo que devuelve. Vacío si el expediente no existe.
   */
  public List<TipoPermitidoDTO> tiposDelProducto(String idExpediente) {
    try {
      return carga.tiposPermitidos(idExpediente);
    } catch (ApiException e) {
      if (e.getStatus() != HttpStatus.NOT_FOUND) {
        throw e;
      }
      return List.of();
    }
  }

  /** Ids de los documentos vigentes del expediente: los únicos que una observación puede citar. */
  @Transactional(readOnly = true)
  public Set<UUID> documentosDelExpediente(UUID caseId, UUID tenantId) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (LegalDocument doc : vigentes(caseId, tenantId)) {
      ids.add(doc.getId());
    }
    return ids;
  }

  /** Abiertas del último estudio de la ficha; vacío si no hay ficha o estudio. */
  @Transactional(readOnly = true)
  public Agrupadas abiertas(UUID caseId, UUID writingFileId, UUID tenantId) {
    if (writingFileId == null) {
      return Agrupadas.VACIAS;
    }
    List<ObservacionCotejoItem> abiertas =
        titleStudies
            .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writingFileId, tenantId)
            .map(study -> ObservacionesCotejo.pendientes(titleObservations, tenantId, study.getId()))
            .orElse(List.of());
    return agrupar(caseId, tenantId, abiertas);
  }

  /**
   * Por documento (orden de carga), faltantes (sin documento, con el tipo que falta) y generales
   * (sin documento ni tipo: red de seguridad y observaciones de estudio-titulo). Una observación
   * cuyo documento ya no está vigente pasa a generales. Los relacionados llevan su nombre y se
   * omiten los que ya no están vigentes.
   */
  @Transactional(readOnly = true)
  public Agrupadas agrupar(UUID caseId, UUID tenantId, List<ObservacionCotejoItem> abiertas) {
    if (abiertas == null || abiertas.isEmpty()) {
      return Agrupadas.VACIAS;
    }
    Map<UUID, LegalDocument> docs = new LinkedHashMap<>();
    for (LegalDocument doc : vigentes(caseId, tenantId)) {
      docs.put(doc.getId(), doc);
    }
    Map<UUID, List<ObservacionCotejoItem>> porId = new LinkedHashMap<>();
    List<ObservacionCotejoItem> generales = new ArrayList<>();
    List<ObservacionCotejoItem> faltantes = new ArrayList<>();
    List<ObservacionCotejoItem> conNombres = new ArrayList<>();
    for (ObservacionCotejoItem original : abiertas) {
      ObservacionCotejoItem item =
          original.conRelacionados(
              original.documentosRelacionados().stream()
                  .filter(rel -> docs.containsKey(rel.documentId()))
                  .map(rel -> new DocumentoRelacionadoItem(rel.documentId(), docs.get(rel.documentId()).getName()))
                  .toList());
      conNombres.add(item);
      if (item.documentoId() != null && docs.containsKey(item.documentoId())) {
        porId.computeIfAbsent(item.documentoId(), id -> new ArrayList<>()).add(item);
      } else if (item.documentoId() == null && item.tipoDocumentoFaltante() != null) {
        faltantes.add(item);
      } else {
        generales.add(item);
      }
    }
    List<ObservacionesDocumentoItem> porDocumento = new ArrayList<>();
    for (LegalDocument doc : docs.values()) {
      List<ObservacionCotejoItem> items = porId.get(doc.getId());
      if (items == null) {
        continue;
      }
      Integer versionNo =
          versions
              .findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(doc.getId(), tenantId)
              .map(DocumentVersion::getVersionNo)
              .orElse(null);
      porDocumento.add(
          new ObservacionesDocumentoItem(
              doc.getId(), doc.getName(), doc.getDocType(), versionNo, List.copyOf(items)));
    }
    return new Agrupadas(
        List.copyOf(conNombres), List.copyOf(porDocumento), List.copyOf(generales), List.copyOf(faltantes));
  }

  private List<LegalDocument> vigentes(UUID caseId, UUID tenantId) {
    return documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(caseId, tenantId);
  }

  public record Agrupadas(
      List<ObservacionCotejoItem> abiertas,
      List<ObservacionesDocumentoItem> porDocumento,
      List<ObservacionCotejoItem> generales,
      List<ObservacionCotejoItem> faltantes) {

    static final Agrupadas VACIAS = new Agrupadas(List.of(), List.of(), List.of(), List.of());
  }
}
