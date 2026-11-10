package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.expedientes.caso.ExpedienteSeguimientoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore.CaseFileContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Reemplazar = versión N+1 del MISMO LegalDocument, nunca un documento nuevo. */
class CaseDocumentoStoreReemplazoTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();

  @TempDir Path almacenamiento;

  private final LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final List<DocumentVersion> guardadas = new ArrayList<>();
  private CaseDocumentoStore store;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(versions.save(any()))
        .thenAnswer(
            llamada -> {
              guardadas.add(llamada.getArgument(0));
              return llamada.getArgument(0);
            });
    when(versions.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(any(), any()))
        .thenAnswer(
            llamada ->
                guardadas.stream()
                    .filter(v -> v.getDocumentId().equals(llamada.getArgument(0)))
                    .max(Comparator.comparingInt(DocumentVersion::getVersionNo)));
    store =
        new CaseDocumentoStore(
            null,
            null,
            documents,
            versions,
            mock(DocumentoTextoOcrRepository.class),
            new ExpedienteDocumentoStorage(almacenamiento.toString()),
            mock(ObjectProvider.class));
  }

  private static CaseFileContext contexto(String modo) {
    return new CaseFileContext(CASO, TENANT, null, null, IngestionMode.from(modo));
  }

  private LegalDocument existente(String nombre, String tipo) {
    LegalDocument doc = LegalDocument.create(TENANT, CASO, nombre, tipo);
    when(documents.findByIdAndTenantIdAndDeletedAtIsNull(doc.getId(), TENANT))
        .thenReturn(Optional.of(doc));
    guardadas.add(DocumentVersion.upload(TENANT, doc.getId(), 1, "application/pdf", "sha", "v1"));
    return doc;
  }

  @Test
  void reemplazarCreaLaVersion2DelMismoDocumento() {
    LegalDocument doc = existente("cedula.pdf", "CEDULA");
    CaseFileContext ctx = contexto("DIGITAL_SEPARADO");

    StoredDoc nuevo =
        store.reemplazar(
            ctx, doc.getId().toString(), "cedula-nueva.pdf", "application/pdf", new byte[] {9}, null);

    assertEquals(doc.getId().toString(), nuevo.idDocumento());
    assertEquals(2, guardadas.size());
    DocumentVersion v2 = guardadas.get(1);
    assertEquals(doc.getId(), v2.getDocumentId());
    assertEquals(2, v2.getVersionNo());
    assertEquals(2, store.versionActual(ctx, doc.getId().toString()));
    ArgumentCaptor<LegalDocument> documento = ArgumentCaptor.forClass(LegalDocument.class);
    verify(documents, times(1)).save(documento.capture());
    assertEquals(doc.getId(), documento.getValue().getId(), "no se crea otro LegalDocument");
  }

  @Test
  void enEscaneadoReemplazarEsSubirElEscaneoCompletoSinConflicto() {
    LegalDocument doc =
        existente("expediente.pdf", IngestionMode.EXPEDIENTE_FISICO_ESCANEADO);
    CaseFileContext ctx = contexto("FISICO_ESCANEADO");

    StoredDoc nuevo =
        store.reemplazar(
            ctx, doc.getId().toString(), "escaneo-completo.pdf", "application/pdf", new byte[] {7}, "CEDULA");

    assertEquals(IngestionMode.EXPEDIENTE_FISICO_ESCANEADO, nuevo.codigoTipoDocumento());
    assertEquals(2, store.versionActual(ctx, doc.getId().toString()));
  }
}
