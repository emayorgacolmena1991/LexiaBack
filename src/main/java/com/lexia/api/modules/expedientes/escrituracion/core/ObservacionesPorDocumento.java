package com.lexia.api.modules.expedientes.escrituracion.core;

import com.lexia.api.modules.expedientes.documentos.DocumentVersion;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionCotejoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionesDocumentoItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

  public ObservacionesPorDocumento(
      LegalDocumentRepository documents,
      DocumentVersionRepository versions,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations) {
    this.documents = documents;
    this.versions = versions;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
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
   * Una observación cuyo documento ya no está vigente (eliminado después) pasa a generales: no hay
   * documento que mostrar ni que reemplazar. Los documentos salen en el orden de carga.
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
    for (ObservacionCotejoItem item : abiertas) {
      if (item.documentoId() != null && docs.containsKey(item.documentoId())) {
        porId.computeIfAbsent(item.documentoId(), id -> new ArrayList<>()).add(item);
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
    return new Agrupadas(List.copyOf(abiertas), List.copyOf(porDocumento), List.copyOf(generales));
  }

  private List<LegalDocument> vigentes(UUID caseId, UUID tenantId) {
    return documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(caseId, tenantId);
  }

  public record Agrupadas(
      List<ObservacionCotejoItem> abiertas,
      List<ObservacionesDocumentoItem> porDocumento,
      List<ObservacionCotejoItem> generales) {

    static final Agrupadas VACIAS = new Agrupadas(List.of(), List.of(), List.of());
  }
}
