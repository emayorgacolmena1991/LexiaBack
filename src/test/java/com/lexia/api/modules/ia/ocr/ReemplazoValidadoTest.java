package com.lexia.api.modules.ia.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.storage.ArchivoStorageLocal;
import com.lexia.api.modules.actos.ActoNotarialService;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService;
import com.lexia.api.modules.expedientes.documentos.DocumentVersion;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.ExpedienteDocumentoStorage;
import com.lexia.api.modules.expedientes.documentos.IngestionMode;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.DocumentoRequisitoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ClasificacionDocumento;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ReuploadResponse;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * POST /documents/reupload valida antes de guardar: OCR y tipo sobre los bytes recibidos, y solo
 * entonces versión N+1, fila OCR, caché y consolidado. Servicios reales; repositorios en memoria.
 */
class ReemplazoValidadoTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final String PRODUCTO = "VIV_HIPOTECADA_BIESS";
  private static final String TEXTO_VIEJO = "CEDULA VIEJA 111";

  @TempDir Path almacenamiento;

  private final List<DocumentVersion> versiones = new ArrayList<>();
  private final Map<String, DocumentoTextoOcr> filas = new LinkedHashMap<>();
  private final AzureCalidadDocumentoService azure = mock(AzureCalidadDocumentoService.class);
  private final AnalisisDocumentoService llm = mock(AnalisisDocumentoService.class);
  private final OcrSessionCacheService cache = new OcrSessionCacheService(2);
  private final LegalCase legalCase = mock(LegalCase.class);
  private LegalDocument documento;
  private OcrAzureBatchService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    when(legalCase.getId()).thenReturn(CASO);
    when(legalCase.getProductCode()).thenReturn(PRODUCTO);
    when(legalCase.getIngestionMode()).thenReturn("DIGITAL_SEPARADO");
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));

    documento = LegalDocument.create(TENANT, CASO, "cedula.pdf", "CEDULA");
    LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
    when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documento.getId(), TENANT))
        .thenReturn(Optional.of(documento));
    when(documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, TENANT))
        .thenReturn(List.of(documento));
    versiones.add(DocumentVersion.upload(TENANT, documento.getId(), 1, "application/pdf", "s1", "v1"));
    DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    when(versions.save(any()))
        .thenAnswer(
            llamada -> {
              versiones.add(llamada.getArgument(0));
              return llamada.getArgument(0);
            });
    when(versions.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(any(), any()))
        .thenAnswer(
            llamada ->
                versiones.stream()
                    .filter(v -> v.getDocumentId().equals(llamada.getArgument(0)))
                    .max(Comparator.comparingInt(DocumentVersion::getVersionNo)));

    DocumentoTextoOcrRepository ocrRows = mock(DocumentoTextoOcrRepository.class);
    when(ocrRows.findByIdExpedienteAndIdDocumento(anyString(), anyString()))
        .thenAnswer(llamada -> Optional.ofNullable(filas.get(llamada.<String>getArgument(1))));
    when(ocrRows.findByIdExpedienteOrderByCreatedAtAsc(CASO.toString()))
        .thenAnswer(llamada -> new ArrayList<>(filas.values()));
    when(ocrRows.save(any()))
        .thenAnswer(
            llamada -> {
              DocumentoTextoOcr fila = llamada.getArgument(0);
              filas.put(fila.getIdDocumento(), fila);
              return fila;
            });
    DocumentoTextoOcr previa = new DocumentoTextoOcr();
    previa.setIdExpediente(CASO.toString());
    previa.setIdDocumento(documento.getId().toString());
    previa.setTipoDocumento("CEDULA");
    previa.setTextoOcr(TEXTO_VIEJO);
    filas.put(previa.getIdDocumento(), previa);
    cache.putResult(
        CASO.toString(),
        new OcrFileResult(documento.getId().toString(), "cedula.pdf", "CEDULA", true, null, 0.9, TEXTO_VIEJO));

    CaseDocumentoStore caseDocs =
        new CaseDocumentoStore(
            legalCases,
            mock(WritingFileRepository.class),
            documents,
            versions,
            ocrRows,
            new ExpedienteDocumentoStorage(new ArchivoStorageLocal(almacenamiento)),
            mock(ObjectProvider.class));
    ProductoBiessService productos = mock(ProductoBiessService.class);
    when(productos.detalle(PRODUCTO, null))
        .thenReturn(
            new ProductoDetalle(
                PRODUCTO,
                "BIESS",
                List.of(
                    new DocumentoRequisitoItem("CEDULA", "Cédula", true, 0, null, 1),
                    new DocumentoRequisitoItem("AVALUO", "Avalúo", true, 0, null, 2)),
                List.of()));
    CargaDocumentoService carga =
        new CargaDocumentoService(
            mock(ActoNotarialService.class),
            null,
            ocrRows,
            productos,
            mock(ExpedienteBorradorStore.class),
            caseDocs,
            mock(ObjectProvider.class));
    ObjectProvider<AnalisisDocumentoService> proveedor = mock(ObjectProvider.class);
    doReturn(llm).when(proveedor).getIfAvailable();
    ClasificacionDocumentoService clasificador =
        new ClasificacionDocumentoService(caseDocs, carga, proveedor, 0.8, 5000);
    when(azure.isConfigured()).thenReturn(true);
    service =
        new OcrAzureBatchService(
            azure, carga, cache, ocrRows, Runnable::run, clasificador, mock(PlatformTransactionManager.class));
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  private static MockMultipartFile pdf(String contenido) {
    return new MockMultipartFile(
        "file", "nuevo.pdf", "application/pdf", ("%PDF-1.4 " + contenido).getBytes(StandardCharsets.UTF_8));
  }

  private void azureLee(String texto) {
    when(azure.evaluarYExtraer(any(), any()))
        .thenReturn(new LayoutExtractResult(new CalidadDocumentoResultado(true, "OK", 0.95, 10, 0.7, null), texto));
  }

  private void llmResponde(String tipo) {
    when(llm.clasificarDocumento(anyString(), any(), any()))
        .thenReturn(new ClasificacionDocumento(tipo, 0.95, "claude-test"));
  }

  private ReuploadResponse reemplazar(MockMultipartFile archivo, boolean confirmar) {
    return service.reupload(CASO.toString(), documento.getId().toString(), null, archivo, confirmar);
  }

  private String textoFila() {
    return filas.get(documento.getId().toString()).getTextoOcr();
  }

  private long archivosGuardados() throws Exception {
    try (Stream<Path> todos = Files.walk(almacenamiento)) {
      return todos.filter(Files::isRegularFile).count();
    }
  }

  @Test
  void ocrFallidoNoPersisteNadaYConservaElTextoAnterior() throws Exception {
    when(azure.evaluarYExtraer(any(), any())).thenThrow(new IllegalStateException("Azure 503"));

    ReuploadResponse r = reemplazar(pdf("ilegible"), false);

    assertFalse(r.guardado());
    assertFalse(r.ocrOk());
    assertEquals(1, versiones.size(), "sin versión nueva");
    assertEquals(1, r.versionNo());
    assertEquals(TEXTO_VIEJO, textoFila());
    assertEquals(TEXTO_VIEJO, r.textoExtraido());
    assertThat(filas).doesNotContainKey("consolidado");
    assertEquals(0, archivosGuardados());
    verify(llm, never()).clasificarDocumento(anyString(), any(), any());
  }

  @Test
  void tipoDistintoSinConfirmarNoPersisteNadaYPideConfirmacion() throws Exception {
    azureLee("AVALUO USD 95000");
    llmResponde("AVALUO");

    ReuploadResponse r = reemplazar(pdf("avaluo"), false);

    assertFalse(r.guardado());
    assertTrue(r.ocrOk());
    assertTrue(r.requiereConfirmacion());
    assertEquals("AVALUO", r.tipoDetectado());
    assertEquals(1, versiones.size());
    assertEquals(TEXTO_VIEJO, textoFila());
    assertEquals(0, archivosGuardados());
    assertNull(documento.getTipoSugerido(), "tampoco se guarda la clasificación");
    assertEquals(LegalDocument.ESTADO_PENDIENTE, documento.getClasificacionEstado());
  }

  @Test
  void conConfirmacionGuardaLaVersion2SinRepetirOcrNiClasificacion() throws Exception {
    azureLee("AVALUO USD 95000");
    llmResponde("AVALUO");
    MockMultipartFile archivo = pdf("avaluo");
    reemplazar(archivo, false);

    ReuploadResponse r = reemplazar(archivo, true);

    assertTrue(r.guardado());
    assertEquals(2, r.versionNo());
    assertEquals(2, versiones.size());
    assertEquals(documento.getId(), versiones.get(1).getDocumentId());
    assertEquals("AVALUO USD 95000", textoFila());
    assertEquals(Boolean.FALSE, r.coincide());
    assertEquals("CEDULA", documento.getDocType(), "conserva su tipo");
    assertEquals(LegalDocument.ESTADO_POR_REVISAR, documento.getClasificacionEstado());
    assertEquals(1, archivosGuardados());
    verify(azure, times(1)).evaluarYExtraer(any(), any());
    verify(llm, times(1)).clasificarDocumento(anyString(), any(), any());
    assertThat(cache.listResults(CASO.toString()))
        .singleElement()
        .satisfies(c -> assertEquals("AVALUO USD 95000", c.textoExtraido()));
    assertThat(filas.get("consolidado").getTextoOcr()).contains("AVALUO USD 95000").doesNotContain(TEXTO_VIEJO);
  }

  @Test
  void otroArchivoAlConfirmarRepiteElOcr() {
    azureLee("AVALUO USD 95000");
    llmResponde("AVALUO");
    reemplazar(pdf("avaluo"), false);

    assertTrue(reemplazar(pdf("otro avaluo"), true).guardado());
    verify(azure, times(2)).evaluarYExtraer(any(), any());
  }

  @Test
  void clasificacionEnErrorNoBloqueaYGuarda() {
    azureLee("CEDULA NUEVA 222");
    when(llm.clasificarDocumento(anyString(), any(), any())).thenThrow(new IllegalStateException("Claude 529"));

    ReuploadResponse r = reemplazar(pdf("cedula"), false);

    assertTrue(r.guardado());
    assertEquals(Boolean.TRUE, r.coincide(), "ERROR se trata como coincidente");
    assertNull(r.tipoDetectado());
    assertEquals(2, versiones.size());
    assertEquals("CEDULA NUEVA 222", textoFila());
    assertEquals(LegalDocument.ESTADO_ERROR, documento.getClasificacionEstado());
  }

  @Test
  void mismoTipoGuardaDirecto() {
    azureLee("CEDULA NUEVA 222");
    llmResponde("CEDULA");

    ReuploadResponse r = reemplazar(pdf("cedula"), false);

    assertTrue(r.guardado());
    assertFalse(r.requiereConfirmacion());
    assertEquals(Boolean.TRUE, r.coincide());
    assertEquals(2, r.versionNo());
    assertEquals("CEDULA NUEVA 222", textoFila());
    assertEquals(LegalDocument.ESTADO_CLASIFICADO, documento.getClasificacionEstado());
  }

  @Test
  void escaneadoGuardaTrasElOcrSinClasificar() {
    when(legalCase.getIngestionMode()).thenReturn("FISICO_ESCANEADO");
    documento.classify(IngestionMode.EXPEDIENTE_FISICO_ESCANEADO);
    azureLee("EXPEDIENTE COMPLETO");

    ReuploadResponse r = reemplazar(pdf("escaneo"), false);

    assertTrue(r.guardado());
    assertNull(r.coincide());
    assertEquals(2, versiones.size());
    assertEquals("EXPEDIENTE COMPLETO", textoFila());
    verify(llm, never()).clasificarDocumento(anyString(), any(), any());
  }
}
