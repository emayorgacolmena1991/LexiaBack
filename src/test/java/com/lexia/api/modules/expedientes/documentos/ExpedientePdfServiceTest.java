package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.CaseReadScope;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpedientePdfServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final UUID MIA = UUID.randomUUID();

  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
  private final LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final ExpedienteDocumentoStorage storage = mock(ExpedienteDocumentoStorage.class);
  private final LegalCase legalCase = mock(LegalCase.class);
  private ExpedientePdfService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, MIA));
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT))
        .thenReturn(Optional.of(legalCase));
    service = new ExpedientePdfService(authorization, legalCases, documents, versions, storage);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void uneLosPdfYDejaFueraLoQueNoLoEs() throws Exception {
    assertTrue(ExpedientePdfService.esPdf("application/pdf", "foto.jpg"));
    assertFalse(ExpedientePdfService.esPdf("image/jpeg", "foto.jpg"));
    byte[] unido = ExpedientePdfService.unirPdf(List.of(pdf(), pdf()));
    try (PDDocument doc = Loader.loadPDF(unido)) {
      assertEquals(2, doc.getNumberOfPages());
    }
  }

  @Test
  void expedienteDeOtroResponsableNoSeDescarga() {
    when(authorization.hasPermission(CaseReadScope.READ_ALL)).thenReturn(false);
    when(legalCase.getResponsibleMembershipId()).thenReturn(UUID.randomUUID());

    assertThrows(AuthException.class, () -> service.unir(CASO));
    verify(documents, never()).findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(any(), any());
  }

  @Test
  void demasiadosDocumentosNoSeUnenEnElRequest() {
    when(legalCase.getResponsibleMembershipId()).thenReturn(MIA);
    LegalDocument doc = mock(LegalDocument.class);
    when(documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, TENANT))
        .thenReturn(Collections.nCopies(ExpedientePdfService.MAX_DOCUMENTOS + 1, doc));

    ApiException ex = assertThrows(ApiException.class, () -> service.unir(CASO));
    assertTrue(ex.getMessage().contains("por separado"), ex.getMessage());
    verify(storage, never()).read(any());
  }

  @Test
  void pdfsQueSuperanElTopeDeBytesNoSeLeen() {
    when(legalCase.getResponsibleMembershipId()).thenReturn(MIA);
    LegalDocument doc = mock(LegalDocument.class);
    when(doc.getId()).thenReturn(UUID.randomUUID());
    when(doc.getName()).thenReturn("a.pdf");
    DocumentVersion version = mock(DocumentVersion.class);
    when(version.getMimeType()).thenReturn("application/pdf");
    when(version.getStorageKey()).thenReturn("k");
    when(documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, TENANT))
        .thenReturn(List.of(doc, doc));
    when(versions.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(any(), any()))
        .thenReturn(Optional.of(version));
    when(storage.size("k")).thenReturn(ExpedientePdfService.MAX_BYTES / 2 + 1);

    assertThrows(ApiException.class, () -> service.unir(CASO));
    verify(storage, never()).read(any());
  }

  private static byte[] pdf() throws Exception {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage());
      doc.save(out);
      return out.toByteArray();
    }
  }
}
