package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.caso.ExpedienteSeguimientoService;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore.CaseFileContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CaseDocumentoStoreEliminarTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();

  private final LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
  private final DocumentoTextoOcrRepository ocr = mock(DocumentoTextoOcrRepository.class);
  private final ExpedienteSeguimientoService seguimiento = mock(ExpedienteSeguimientoService.class);
  private final CaseFileContext contexto =
      new CaseFileContext(CASO, TENANT, null, null, IngestionMode.from("DIGITAL_SEPARADO"));
  private CaseDocumentoStore store;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ObjectProvider<ExpedienteSeguimientoService> provider =
        mock(ObjectProvider.class, CALLS_REAL_METHODS);
    doReturn(seguimiento).when(provider).getIfAvailable();
    store = new CaseDocumentoStore(null, null, documents, null, ocr, null, provider);
  }

  @Test
  void eliminarDejaConstanciaDelDocumento() {
    LegalDocument doc = LegalDocument.create(TENANT, CASO, "CHD.pdf", "HISTORIA_DOMINIO");
    when(documents.findByIdAndTenantIdAndDeletedAtIsNull(doc.getId(), TENANT))
        .thenReturn(Optional.of(doc));
    when(ocr.findByIdExpedienteAndIdDocumento(CASO.toString(), doc.getId().toString()))
        .thenReturn(Optional.empty());

    store.eliminar(contexto, doc.getId().toString());

    verify(seguimiento).documentoEliminado(CASO, TENANT, doc.getId(), "CHD.pdf");
  }

  @Test
  void documentoAjenoNoGeneraEvento() {
    LegalDocument ajeno = LegalDocument.create(TENANT, UUID.randomUUID(), "x.pdf", null);
    when(documents.findByIdAndTenantIdAndDeletedAtIsNull(ajeno.getId(), TENANT))
        .thenReturn(Optional.of(ajeno));

    assertThrows(ApiException.class, () -> store.eliminar(contexto, ajeno.getId().toString()));

    verifyNoInteractions(seguimiento);
  }
}
