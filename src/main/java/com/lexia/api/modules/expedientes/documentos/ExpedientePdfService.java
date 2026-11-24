package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.caso.CaseReadScope;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Une los PDF vigentes del expediente. Lo que no es PDF se omite y se devuelve en el aviso. Se
 * hace en el request (no es un job): por eso lleva tope de documentos y de bytes, y el merge usa
 * archivos temporales en lugar de memoria.
 */
@Service
public class ExpedientePdfService {

  private static final Logger LOG = LoggerFactory.getLogger(ExpedientePdfService.class);

  /** 25 MB por documento (tope de subida) × 6: un expediente normal queda muy por debajo. */
  static final long MAX_BYTES = 150L * 1024 * 1024;
  static final int MAX_DOCUMENTOS = 100;

  public record PdfExpediente(String codigo, List<String> omitidos, byte[] pdf) {}

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final LegalDocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final ExpedienteDocumentoStorage storage;

  public ExpedientePdfService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      LegalDocumentRepository documents,
      DocumentVersionRepository versions,
      ExpedienteDocumentoStorage storage) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.documents = documents;
    this.versions = versions;
    this.storage = storage;
  }

  @Transactional(readOnly = true)
  public PdfExpediente unir(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () ->
                    new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
    CaseReadScope.assertCanRead(authorization, legalCase.getResponsibleMembershipId());

    List<LegalDocument> docs =
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(caseId, tenantId);
    if (docs.size() > MAX_DOCUMENTOS) {
      throw ApiException.badRequest(
          "El expediente tiene más de " + MAX_DOCUMENTOS + " documentos; descárgalos por separado.");
    }
    List<String> claves = new ArrayList<>();
    List<String> omitidos = new ArrayList<>();
    long total = 0;
    for (LegalDocument doc : docs) {
      DocumentVersion version =
          versions
              .findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(doc.getId(), tenantId)
              .orElse(null);
      if (version == null || !esPdf(version.getMimeType(), doc.getName())) {
        omitidos.add(doc.getName());
        continue;
      }
      long bytes = storage.size(version.getStorageKey());
      if (bytes <= 0) {
        LOG.warn("PDF expediente case={} doc={} sin archivo en storage", caseId, doc.getId());
        omitidos.add(doc.getName());
        continue;
      }
      total += bytes;
      if (total > MAX_BYTES) {
        throw ApiException.badRequest(
            "Los PDF del expediente superan " + (MAX_BYTES / (1024 * 1024)) + " MB; descárgalos por separado.");
      }
      claves.add(version.getStorageKey());
    }
    if (claves.isEmpty()) {
      throw ApiException.badRequest("No hay documentos PDF vigentes para unir.");
    }
    List<byte[]> pdfs = new ArrayList<>(claves.size());
    for (String clave : claves) {
      pdfs.add(storage.read(clave));
    }
    byte[] unido = unirPdf(pdfs);
    LOG.info(
        "PDF expediente case={} incluidos={} omitidos={} bytes={}",
        caseId,
        pdfs.size(),
        omitidos.size(),
        unido.length);
    return new PdfExpediente(legalCase.getCode(), List.copyOf(omitidos), unido);
  }

  static byte[] unirPdf(List<byte[]> pdfs) {
    try {
      PDFMergerUtility merger = new PDFMergerUtility();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      merger.setDestinationStream(out);
      for (byte[] pdf : pdfs) {
        merger.addSource(new RandomAccessReadBuffer(new ByteArrayInputStream(pdf)));
      }
      merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
      return out.toByteArray();
    } catch (IOException ex) {
      throw ApiException.badRequest("No se pudieron unir los PDF del expediente.");
    }
  }

  static boolean esPdf(String mime, String nombre) {
    if (mime != null && mime.toLowerCase(Locale.ROOT).contains("pdf")) {
      return true;
    }
    return nombre != null && nombre.toLowerCase(Locale.ROOT).endsWith(".pdf");
  }
}
