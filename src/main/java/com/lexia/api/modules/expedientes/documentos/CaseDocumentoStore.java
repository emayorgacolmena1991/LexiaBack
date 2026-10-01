package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.PrevalidacionDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.PrevalidacionDocumentoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.TipoActualizadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.escrituracion.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.WritingFileRepository;
import com.lexia.api.modules.expedientes.caso.ExpedienteSeguimientoService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Documentos del expediente oficial ({@code legal_case}). Reemplaza el mapa en memoria del
 * borrador una vez promovido.
 */
@Service
public class CaseDocumentoStore {

  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final LegalDocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final DocumentoTextoOcrRepository ocrRows;
  private final ExpedienteDocumentoStorage storage;
  private final ObjectProvider<ExpedienteSeguimientoService> seguimiento;

  public CaseDocumentoStore(
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      LegalDocumentRepository documents,
      DocumentVersionRepository versions,
      DocumentoTextoOcrRepository ocrRows,
      ExpedienteDocumentoStorage storage,
      ObjectProvider<ExpedienteSeguimientoService> seguimiento) {
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.documents = documents;
    this.versions = versions;
    this.ocrRows = ocrRows;
    this.storage = storage;
    this.seguimiento = seguimiento;
  }

  public CaseFileContext context(String rawId) {
    UUID id = parse(rawId);
    AuthPrincipal auth = AuthContext.get();
    if (id == null || auth == null || auth.tenantId() == null) {
      return null;
    }
    LegalCase legalCase =
        legalCases.findByIdAndTenantIdAndDeletedAtIsNull(id, auth.tenantId()).orElse(null);
    if (legalCase == null) {
      return null;
    }
    WritingFile writing =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(legalCase.getId(), auth.tenantId())
            .orElse(null);
    String product =
        writing != null && StringUtils.hasText(writing.getProductCode())
            ? writing.getProductCode()
            : legalCase.getProductCode();
    String canton = writing == null ? null : writing.getCanton();
    String modeName =
        writing != null && StringUtils.hasText(writing.getIngestionMode())
            ? writing.getIngestionMode()
            : legalCase.getIngestionMode();
    return new CaseFileContext(
        legalCase.getId(), auth.tenantId(), product, canton, IngestionMode.from(modeName));
  }

  public boolean isCase(String rawId) {
    return context(rawId) != null;
  }

  @Transactional(readOnly = true)
  public List<DocumentoCargadoDTO> listar(CaseFileContext ctx) {
    List<DocumentoCargadoDTO> rows = new ArrayList<>();
    for (LegalDocument doc :
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            ctx.caseId(), ctx.tenantId())) {
      DocumentVersion version = latest(doc.getId(), ctx.tenantId());
      long bytes = version == null ? 0 : storage.size(version.getStorageKey());
      rows.add(
          new DocumentoCargadoDTO(
              doc.getId().toString(), doc.getName(), formatSize(bytes), doc.getDocType()));
    }
    return rows;
  }

  @Transactional
  public DocumentoCargadoDTO subir(CaseFileContext ctx, String original, String mime, byte[] bytes) {
    if (ctx.ingestionMode().isFisicoEscaneado()
        && !documents
            .findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                ctx.caseId(), ctx.tenantId())
            .isEmpty()) {
      throw ApiException.badRequest("En modo físico escaneado solo se admite un PDF.");
    }
    String tipo =
        ctx.ingestionMode().isFisicoEscaneado() ? IngestionMode.EXPEDIENTE_FISICO_ESCANEADO : null;
    LegalDocument doc = LegalDocument.create(ctx.tenantId(), ctx.caseId(), original, tipo);
    ExpedienteDocumentoStorage.Stored stored =
        storage.write(ctx.tenantId(), ctx.caseId(), doc.getId(), original, bytes);
    documents.save(doc);
    versions.save(
        DocumentVersion.upload(
            ctx.tenantId(), doc.getId(), 1, mime, stored.sha256(), stored.path()));
    seguimiento.ifAvailable(
        service -> service.documentoSubido(ctx.caseId(), ctx.tenantId(), original));
    return new DocumentoCargadoDTO(doc.getId().toString(), original, formatSize(bytes.length), tipo);
  }

  @Transactional
  public TipoActualizadoDTO actualizarTipo(CaseFileContext ctx, String idDocumento, String codigo) {
    LegalDocument doc = requireDoc(ctx, idDocumento);
    if (ctx.ingestionMode().isFisicoEscaneado()) {
      doc.classify(IngestionMode.EXPEDIENTE_FISICO_ESCANEADO);
    } else {
      doc.classify(codigo);
    }
    documents.save(doc);
    return new TipoActualizadoDTO(doc.getId().toString(), doc.getDocType(), "CLASIFICADO");
  }

  @Transactional
  public void eliminar(CaseFileContext ctx, String idDocumento) {
    LegalDocument doc = requireDoc(ctx, idDocumento);
    doc.softDelete();
    documents.save(doc);
    ocrRows
        .findByIdExpedienteAndIdDocumento(ctx.caseId().toString(), doc.getId().toString())
        .ifPresent(ocrRows::delete);
  }

  @Transactional(readOnly = true)
  public StoredDoc requireStoredDoc(CaseFileContext ctx, String fileId) {
    LegalDocument doc = requireDoc(ctx, fileId);
    DocumentVersion version = latest(doc.getId(), ctx.tenantId());
    if (version == null) {
      throw ApiException.notFound("Documento no encontrado: " + fileId);
    }
    byte[] bytes = storage.read(version.getStorageKey());
    return new StoredDoc(
        doc.getId().toString(),
        doc.getName(),
        bytes.length,
        formatSize(bytes.length),
        version.getMimeType(),
        bytes,
        doc.getDocType());
  }

  @Transactional
  public StoredDoc reemplazar(
      CaseFileContext ctx, String fileId, String original, String mime, byte[] bytes, String tipo) {
    LegalDocument doc = requireDoc(ctx, fileId);
    String tipoFinal =
        ctx.ingestionMode().isFisicoEscaneado()
            ? IngestionMode.EXPEDIENTE_FISICO_ESCANEADO
            : (StringUtils.hasText(tipo) ? tipo.trim() : doc.getDocType());
    doc.rename(original, tipoFinal);
    documents.save(doc);
    int next =
        versions
            .findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(doc.getId(), ctx.tenantId())
            .map(version -> version.getVersionNo() + 1)
            .orElse(1);
    ExpedienteDocumentoStorage.Stored stored =
        storage.write(ctx.tenantId(), ctx.caseId(), doc.getId(), original, bytes);
    versions.save(
        DocumentVersion.upload(
            ctx.tenantId(), doc.getId(), next, mime, stored.sha256(), stored.path()));
    return new StoredDoc(
        doc.getId().toString(),
        doc.getName(),
        bytes.length,
        formatSize(bytes.length),
        mime,
        bytes,
        doc.getDocType());
  }

  @Transactional
  public void guardarOcr(CaseFileContext ctx, DocumentoOcrResultadoDTO resultado) {
    if (resultado == null || !StringUtils.hasText(resultado.idDocumento())) {
      return;
    }
    DocumentoTextoOcr row =
        ocrRows
            .findByIdExpedienteAndIdDocumento(ctx.caseId().toString(), resultado.idDocumento())
            .orElseGet(DocumentoTextoOcr::new);
    row.setTenantId(ctx.tenantId());
    row.setIdExpediente(ctx.caseId().toString());
    row.setIdDocumento(resultado.idDocumento());
    row.setTipoDocumento(resultado.tipoDocumento());
    row.setTextoOcr(resultado.textoOcr());
    row.setAnalisisJson(resultado.analisisJson());
    row.setEstadoDoc(
        StringUtils.hasText(resultado.estado()) ? resultado.estado() : "EN_PROCESO");
    row.setMotivo(resultado.motivo());
    row.setConfianza(resultado.confianza());
    ocrRows.save(row);
  }

  @Transactional(readOnly = true)
  public List<DocumentoOcrResultadoDTO> listarOcr(CaseFileContext ctx) {
    Map<String, String> names = new LinkedHashMap<>();
    for (LegalDocument doc :
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            ctx.caseId(), ctx.tenantId())) {
      names.put(doc.getId().toString(), doc.getName());
    }
    List<DocumentoOcrResultadoDTO> rows = new ArrayList<>();
    for (DocumentoTextoOcr row : ocrRows.findByIdExpedienteOrderByCreatedAtAsc(ctx.caseId().toString())) {
      if (!names.containsKey(row.getIdDocumento())) {
        continue;
      }
      rows.add(
          new DocumentoOcrResultadoDTO(
              row.getIdDocumento(),
              names.get(row.getIdDocumento()),
              row.getTipoDocumento(),
              row.getTextoOcr(),
              row.getAnalisisJson(),
              row.getEstadoDoc(),
              row.getMotivo(),
              row.getConfianza()));
    }
    return rows;
  }

  @Transactional(readOnly = true)
  public PrevalidacionDTO prevalidacion(CaseFileContext ctx) {
    List<DocumentoCargadoDTO> docs = listar(ctx);
    Map<String, DocumentoOcrResultadoDTO> ocr = new LinkedHashMap<>();
    for (DocumentoOcrResultadoDTO row : listarOcr(ctx)) {
      ocr.put(row.idDocumento(), row);
    }
    List<PrevalidacionDocumentoDTO> items = new ArrayList<>();
    int legibles = 0;
    int confianzaSum = 0;
    int confianzaN = 0;
    boolean allDone = !docs.isEmpty();
    for (DocumentoCargadoDTO doc : docs) {
      DocumentoOcrResultadoDTO row = ocr.get(doc.idDocumento());
      String estado = "EN_PROCESO";
      String motivo = null;
      if (row != null) {
        estado = row.estado() == null ? "REVISAR" : row.estado();
        motivo = row.motivo();
        if ("LEGIBLE".equalsIgnoreCase(estado)) {
          legibles++;
        }
        if (row.confianza() != null) {
          confianzaSum += row.confianza();
          confianzaN++;
        }
      } else {
        allDone = false;
      }
      items.add(
          new PrevalidacionDocumentoDTO(
              doc.idDocumento(), doc.nombreOriginal(), doc.codigoTipoDocumento(), estado, motivo));
    }
    int confianza = confianzaN == 0 ? 0 : Math.round((float) confianzaSum / confianzaN);
    String estado = docs.isEmpty() ? "EN_PROCESO" : (allDone ? "COMPLETADA" : "EN_PROCESO");
    return new PrevalidacionDTO(
        ctx.caseId().toString(), estado, confianza, legibles, docs.size(), items);
  }

  private LegalDocument requireDoc(CaseFileContext ctx, String rawId) {
    UUID id = parse(rawId);
    if (id == null) {
      throw ApiException.notFound("Documento no encontrado: " + rawId);
    }
    LegalDocument doc =
        documents.findByIdAndTenantIdAndDeletedAtIsNull(id, ctx.tenantId()).orElse(null);
    if (doc == null || doc.getCaseId() == null || !doc.getCaseId().equals(ctx.caseId())) {
      throw ApiException.notFound("Documento no encontrado: " + rawId);
    }
    return doc;
  }

  private DocumentVersion latest(UUID documentId, UUID tenantId) {
    return versions
        .findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(documentId, tenantId)
        .orElse(null);
  }

  private static UUID parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  static String formatSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    double kb = bytes / 1024.0;
    if (kb < 1024) {
      return (kb < 10 ? String.format(Locale.ROOT, "%.1f", kb) : String.valueOf(Math.round(kb)))
          + " KB";
    }
    double mb = kb / 1024.0;
    return (mb < 10 ? String.format(Locale.ROOT, "%.1f", mb) : String.valueOf(Math.round(mb)))
        + " MB";
  }

  public record CaseFileContext(
      UUID caseId, UUID tenantId, String productCode, String canton, IngestionMode ingestionMode) {}
}
