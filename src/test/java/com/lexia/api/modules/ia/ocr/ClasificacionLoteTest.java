package com.lexia.api.modules.ia.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.config.AsyncConfig;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.TipoPermitidoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ClasificacionDocumento;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchDocument;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchResponse;
import com.lexia.api.modules.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * analyze-batch con el ejecutor real de AsyncConfig (wrapWithTenantAuth): el clasificador corre en
 * los hilos del pool con el tenant de la petición y el contexto queda limpio después.
 */
class ClasificacionLoteTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();

  private final LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
  private final CargaDocumentoService carga = mock(CargaDocumentoService.class);
  private final AnalisisDocumentoService llm = mock(AnalisisDocumentoService.class);
  private final AzureCalidadDocumentoService azure = mock(AzureCalidadDocumentoService.class);
  /** Tenant visto por el LLM en cada llamada: AuthContext y TenantContext del hilo del pool. */
  private final Map<String, List<UUID>> contextoPorTexto = new ConcurrentHashMap<>();
  private ThreadPoolTaskExecutor pool;
  private Executor ocrExecutor;
  private OcrAzureBatchService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(1);
    pool.setMaxPoolSize(1);
    pool.setThreadNamePrefix("AzureOCR-test-");
    pool.initialize();
    // Mismo envoltorio que el bean ocrExecutor, sobre un pool de un hilo para revisar la limpieza.
    ocrExecutor = ReflectionTestUtils.invokeMethod(AsyncConfig.class, "wrapWithTenantAuth", pool);

    LegalCase legalCase = mock(LegalCase.class);
    when(legalCase.getId()).thenReturn(CASO);
    when(legalCase.getIngestionMode()).thenReturn("DIGITAL_SEPARADO");
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));
    CaseDocumentoStore caseDocs =
        new CaseDocumentoStore(
            legalCases,
            mock(WritingFileRepository.class),
            documents,
            mock(DocumentVersionRepository.class),
            mock(DocumentoTextoOcrRepository.class),
            null,
            mock(ObjectProvider.class));
    when(carga.tiposPermitidos(CASO.toString()))
        .thenReturn(List.of(new TipoPermitidoDTO("CEDULA", "Cédula"), new TipoPermitidoDTO("AVALUO", "Avalúo")));
    ObjectProvider<AnalisisDocumentoService> proveedor = mock(ObjectProvider.class);
    doReturn(llm).when(proveedor).getIfAvailable();
    ClasificacionDocumentoService clasificador =
        new ClasificacionDocumentoService(caseDocs, carga, proveedor, 0.8, 5000);

    when(azure.isConfigured()).thenReturn(true);
    when(azure.evaluarYExtraer(any(), any()))
        .thenAnswer(
            llamada ->
                new LayoutExtractResult(
                    new CalidadDocumentoResultado(true, "OK", 0.95, 10, 0.7, null),
                    new String(llamada.<byte[]>getArgument(0), StandardCharsets.UTF_8)));
    when(llm.clasificarDocumento(anyString(), any(), any()))
        .thenAnswer(
            llamada -> {
              String texto = llamada.getArgument(0);
              AuthPrincipal principal = AuthContext.get();
              contextoPorTexto.put(
                  texto,
                  List.of(
                      principal == null ? UUID.randomUUID() : principal.tenantId(),
                      TenantContext.getTenantId() == null ? UUID.randomUUID() : TenantContext.getTenantId()));
              if (texto.contains("FALLA")) {
                throw new IllegalStateException("Claude 529");
              }
              return new ClasificacionDocumento(texto.startsWith("CEDULA") ? "CEDULA" : "AVALUO", 0.95, "claude-test");
            });

    service =
        new OcrAzureBatchService(
            azure, carga, new OcrSessionCacheService(2), mock(DocumentoTextoOcrRepository.class), ocrExecutor, clasificador,
            mock(org.springframework.transaction.PlatformTransactionManager.class));
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
    TenantContext.clear();
    pool.shutdown();
  }

  private LegalDocument documento(String texto) {
    LegalDocument doc = LegalDocument.create(TENANT, CASO, texto + ".pdf", null);
    when(documents.findByIdAndTenantIdAndDeletedAtIsNull(doc.getId(), TENANT)).thenReturn(Optional.of(doc));
    byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
    when(carga.requireStoredDoc(CASO.toString(), doc.getId().toString()))
        .thenReturn(
            new StoredDoc(doc.getId().toString(), texto + ".pdf", bytes.length, "1 KB", "application/pdf", bytes, null));
    return doc;
  }

  private static AnalyzeBatchDocument enLote(LegalDocument doc) {
    return new AnalyzeBatchDocument(doc.getId().toString(), doc.getName(), null, null);
  }

  @Test
  void loteDeTresClasificaCadaUnoConElTenantDeLaPeticionYLimpiaElHilo() throws Exception {
    LegalDocument cedula = documento("CEDULA 0912345678");
    LegalDocument avaluo = documento("AVALUO USD 95000");
    LegalDocument otraCedula = documento("CEDULA 1712345678");
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    TenantContext.setTenantId(TENANT);

    AnalyzeBatchResponse respuesta =
        service.analyzeBatch(
            new AnalyzeBatchRequest(CASO.toString(), List.of(enLote(cedula), enLote(avaluo), enLote(otraCedula))));

    assertEquals(3, respuesta.results().size());
    assertEquals("CEDULA", cedula.getDocType());
    assertEquals("AVALUO", avaluo.getDocType());
    assertEquals("CEDULA", otraCedula.getDocType());
    assertThat(List.of(cedula, avaluo, otraCedula))
        .allSatisfy(d -> assertEquals(LegalDocument.ORIGEN_AUTO, d.getClasificacionOrigen()));
    assertThat(contextoPorTexto).hasSize(3);
    assertThat(contextoPorTexto.values()).allSatisfy(t -> assertEquals(List.of(TENANT, TENANT), t));
    assertThat(respuesta.results())
        .extracting(r -> r.tipoDocumento())
        .containsExactly("CEDULA", "AVALUO", "CEDULA");

    // El mismo (único) hilo del pool, invocado sin contexto, no conserva el de la petición anterior.
    AuthContext.clear();
    TenantContext.clear();
    CompletableFuture<List<Object>> sonda = new CompletableFuture<>();
    ocrExecutor.execute(
        () -> sonda.complete(java.util.Arrays.asList(AuthContext.get(), TenantContext.getTenantId())));
    List<Object> enElHilo = sonda.get(5, TimeUnit.SECONDS);
    assertNull(enElHilo.get(0));
    assertNull(enElHilo.get(1));
  }

  @Test
  void falloDelLlmEnUnDocumentoDelLoteSoloLoDejaEnError() {
    LegalDocument cedula = documento("CEDULA 0912345678");
    LegalDocument falla = documento("AVALUO FALLA");
    LegalDocument avaluo = documento("AVALUO USD 95000");
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    TenantContext.setTenantId(TENANT);

    AnalyzeBatchResponse respuesta =
        service.analyzeBatch(
            new AnalyzeBatchRequest(CASO.toString(), List.of(enLote(cedula), enLote(falla), enLote(avaluo))));

    assertEquals("COMPLETED", respuesta.status(), "el OCR del lote no se rompe");
    assertEquals(3, respuesta.results().size());
    assertEquals(LegalDocument.ESTADO_ERROR, falla.getClasificacionEstado());
    assertNull(falla.getDocType());
    assertEquals(LegalDocument.ESTADO_CLASIFICADO, cedula.getClasificacionEstado());
    assertEquals(LegalDocument.ESTADO_CLASIFICADO, avaluo.getClasificacionEstado());
    assertEquals("AVALUO", avaluo.getDocType());
    org.mockito.Mockito.verify(carga, org.mockito.Mockito.times(3))
        .guardarResultadoOcr(eq(CASO.toString()), any());
  }
}
