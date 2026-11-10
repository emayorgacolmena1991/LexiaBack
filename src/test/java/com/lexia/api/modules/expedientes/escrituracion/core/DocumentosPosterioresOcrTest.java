package com.lexia.api.modules.expedientes.escrituracion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService.Ejecucion;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.ocr.AzureCalidadDocumentoService;
import com.lexia.api.modules.ia.ocr.CalidadDocumentoResultado;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore;
import com.lexia.api.modules.ia.ocr.LayoutExtractResult;
import com.lexia.api.modules.ia.ocr.OcrAzureBatchService;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ReuploadResponse;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import com.lexia.api.modules.identity.AuthorizationService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Documentos subidos o reemplazados después del alta: OCR con el pipeline de analyze-single, caché
 * de sesión, fila en {@code documento_texto_ocr}, {@code consolidado} regenerado, y lo que recibe
 * el LLM. La BD es un mapa en memoria compartido por OcrAzureBatchService e IaAnalysisService.
 */
class DocumentosPosterioresOcrTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final UUID BORRADOR = UUID.randomUUID();
  private static final String CEDULA = UUID.randomUUID().toString();
  private static final String AVALUO = UUID.randomUUID().toString();

  private final Map<String, DocumentoTextoOcr> filas = new LinkedHashMap<>();
  private final DocumentoTextoOcrRepository ocrRepo = mock(DocumentoTextoOcrRepository.class);
  private final CargaDocumentoService carga = mock(CargaDocumentoService.class);
  private final AzureCalidadDocumentoService azure = mock(AzureCalidadDocumentoService.class);
  private final OcrSessionCacheService cache = new OcrSessionCacheService(2);
  private final LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
  private final ExpedienteCompletoLlmService llm = mock(ExpedienteCompletoLlmService.class);
  private OcrAzureBatchService ocr;
  private IaAnalysisService ia;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    when(ocrRepo.findByIdExpedienteOrderByCreatedAtAsc(anyString()))
        .thenAnswer(
            llamada ->
                filas.values().stream()
                    .filter(f -> f.getIdExpediente().equals(llamada.getArgument(0)))
                    .toList());
    when(ocrRepo.findByIdExpedienteAndIdDocumento(anyString(), anyString()))
        .thenAnswer(
            llamada -> Optional.ofNullable(filas.get(clave(llamada.getArgument(0), llamada.getArgument(1)))));
    doAnswer(
            llamada -> {
              guardarFila(llamada.getArgument(0), llamada.getArgument(1));
              return null;
            })
        .when(carga)
        .guardarResultadoOcr(anyString(), any());
    when(carga.esExpedientePersistido(CASO.toString())).thenReturn(true);
    when(azure.isConfigured()).thenReturn(true);

    ocr = new OcrAzureBatchService(azure, carga, cache, ocrRepo, Runnable::run);

    LegalCase legalCase = mock(LegalCase.class);
    when(legalCase.getProductCode()).thenReturn("VIV_HIPOTECADA_BIESS");
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT))
        .thenReturn(Optional.of(legalCase));
    when(llm.ejecutar(anyString(), anyString(), anyString()))
        .thenReturn(
            new Ejecucion(
                "biess.v1",
                ExtraccionExpedienteCompleto.ok(
                    new ProcesarExpedienteCompletoPayload(
                        List.of(),
                        new DatosExtraidos(null, null, null),
                        new Dictamen("APPROVED", "Sin observaciones", List.of())))));
    ia =
        new IaAnalysisService(
            legalCases,
            mock(ExpedienteBorradorStore.class),
            mock(WritingFileRepository.class),
            mock(TitleStudyRepository.class),
            mock(TitleObservationRepository.class),
            mock(ExtractedDataRepository.class),
            ocrRepo,
            cache,
            llm,
            mock(DocumentosExtraidosStore.class),
            mock(AuthorizationService.class),
            new ObjectMapper(),
            org.mockito.Mockito.mock(ObservacionesPorDocumento.class));
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  private static String clave(String expediente, String documento) {
    return expediente + "|" + documento;
  }

  private void guardarFila(String expediente, DocumentoOcrResultadoDTO r) {
    DocumentoTextoOcr fila =
        filas.computeIfAbsent(clave(expediente, r.idDocumento()), k -> new DocumentoTextoOcr());
    fila.setIdExpediente(expediente);
    fila.setIdDocumento(r.idDocumento());
    fila.setTipoDocumento(r.tipoDocumento());
    fila.setTextoOcr(r.textoOcr());
  }

  private void filaPrevia(String expediente, String documento, String tipo, String texto) {
    guardarFila(
        expediente,
        new DocumentoOcrResultadoDTO(documento, documento, tipo, texto, null, "LEGIBLE", null, 90));
  }

  private String texto(String expediente, String documento) {
    DocumentoTextoOcr fila = filas.get(clave(expediente, documento));
    return fila == null ? null : fila.getTextoOcr();
  }

  private static StoredDoc archivo(String id, String tipo, String contenido) {
    byte[] bytes = contenido.getBytes(StandardCharsets.UTF_8);
    return new StoredDoc(id, id + ".pdf", bytes.length, "1 KB", "application/pdf", bytes, tipo);
  }

  /** Azure devuelve como texto el contenido del archivo. */
  private void azureLee() {
    when(azure.evaluarYExtraer(any(), any()))
        .thenAnswer(
            llamada ->
                new LayoutExtractResult(
                    new CalidadDocumentoResultado(true, "OK", 0.95, 10, 0.7, null),
                    new String(llamada.<byte[]>getArgument(0), StandardCharsets.UTF_8)));
  }

  private void azureFalla() {
    when(azure.evaluarYExtraer(any(), any())).thenThrow(new IllegalStateException("Azure 503"));
  }

  private String textoEnviadoAlLlm(AnalysisRequestDTO request) {
    ia.analizarExpedienteConPromptProducto(CASO, request);
    ArgumentCaptor<String> enviado = ArgumentCaptor.forClass(String.class);
    verify(llm).ejecutar(enviado.capture(), eq("VIV_HIPOTECADA_BIESS"), anyString());
    return enviado.getValue();
  }

  private static int veces(String texto, String fragmento) {
    return texto.split(java.util.regex.Pattern.quote(fragmento), -1).length - 1;
  }

  @Test
  void uploadPosteriorGuardaFilaOcrActualizaCacheYRegeneraConsolidado() {
    filaPrevia(CASO.toString(), CEDULA, "CEDULA", "CEDULA 0912345678");
    when(carga.requireStoredDoc(CASO.toString(), AVALUO))
        .thenReturn(archivo(AVALUO, "AVALUO", "AVALUO USD 95000"));
    azureLee();

    Boolean ocrOk = ocr.ocrTrasSubir(CASO.toString(), AVALUO);

    assertEquals(Boolean.TRUE, ocrOk);
    assertEquals("AVALUO USD 95000", texto(CASO.toString(), AVALUO));
    assertThat(cache.listResults(CASO.toString()))
        .anyMatch(r -> AVALUO.equals(r.fileId()) && "AVALUO USD 95000".equals(r.textoExtraido()));
    String consolidado = texto(CASO.toString(), "consolidado");
    assertThat(consolidado).contains("CEDULA 0912345678").contains("AVALUO USD 95000");
  }

  @Test
  void uploadEnBorradorNoHaceOcr() {
    assertNull(ocr.ocrTrasSubir(BORRADOR.toString(), "DOC-1234"));
    verify(azure, never()).evaluarYExtraer(any(), any());
  }

  @Test
  void uploadConOcrFallidoRespondeOcrOkFalseSinFilaNueva() {
    when(carga.requireStoredDoc(CASO.toString(), AVALUO))
        .thenReturn(archivo(AVALUO, "AVALUO", "AVALUO"));
    azureFalla();

    assertEquals(Boolean.FALSE, ocr.ocrTrasSubir(CASO.toString(), AVALUO));
    assertNull(texto(CASO.toString(), AVALUO));
    assertNull(texto(CASO.toString(), "consolidado"));
  }

  @Test
  void reemplazoEsVersion2YElLlmRecibeElTextoNuevoYNoElViejo() {
    filaPrevia(CASO.toString(), CEDULA, "CEDULA", "CEDULA VIEJA 111");
    cache.putResult(
        CASO.toString(),
        new OcrFileResult(CEDULA, "cedula.pdf", "CEDULA", true, null, 0.9, "CEDULA VIEJA 111"));
    MockMultipartFile nuevo =
        new MockMultipartFile("file", "cedula.pdf", "application/pdf", "CEDULA NUEVA 222".getBytes());
    when(carga.reemplazarArchivoOcr(CASO.toString(), CEDULA, nuevo, "CEDULA"))
        .thenReturn(archivo(CEDULA, "CEDULA", "CEDULA NUEVA 222"));
    when(carga.versionActual(CASO.toString(), CEDULA)).thenReturn(2);
    azureLee();

    ReuploadResponse respuesta = ocr.reupload(CASO.toString(), CEDULA, "CEDULA", nuevo);

    assertEquals(CEDULA, respuesta.documentId());
    assertEquals(2, respuesta.versionNo());
    assertTrue(respuesta.ocrOk());
    assertEquals("CEDULA", respuesta.tipoDocumento());
    assertEquals("CEDULA NUEVA 222", texto(CASO.toString(), CEDULA));
    assertThat(cache.listResults(CASO.toString()))
        .singleElement()
        .satisfies(r -> assertEquals("CEDULA NUEVA 222", r.textoExtraido()));
    assertThat(texto(CASO.toString(), "consolidado"))
        .contains("CEDULA NUEVA 222")
        .doesNotContain("VIEJA");

    String enviado = textoEnviadoAlLlm(new AnalysisRequestDTO(false, null));
    assertThat(enviado).contains("CEDULA NUEVA 222").doesNotContain("CEDULA VIEJA 111");
    assertEquals(1, veces(enviado, "CEDULA NUEVA 222"), "sin duplicar por el consolidado");
  }

  @Test
  void reemplazoConOcrFallidoConservaVersionYTextoAnterior() {
    filaPrevia(CASO.toString(), CEDULA, "CEDULA", "CEDULA VIEJA 111");
    filaPrevia(CASO.toString(), "consolidado", "CONSOLIDADO", "=== DOCUMENTO: CEDULA ===\nCEDULA VIEJA 111");
    cache.putResult(
        CASO.toString(),
        new OcrFileResult(CEDULA, "cedula.pdf", "CEDULA", true, null, 0.9, "CEDULA VIEJA 111"));
    MockMultipartFile nuevo =
        new MockMultipartFile("file", "cedula.pdf", "application/pdf", "ILEGIBLE".getBytes());
    when(carga.reemplazarArchivoOcr(CASO.toString(), CEDULA, nuevo, null))
        .thenReturn(archivo(CEDULA, "CEDULA", "ILEGIBLE"));
    when(carga.versionActual(CASO.toString(), CEDULA)).thenReturn(2);
    azureFalla();

    ReuploadResponse respuesta = ocr.reupload(CASO.toString(), CEDULA, null, nuevo);

    assertFalse(respuesta.ocrOk(), "200 con ocrOk=false, no 5xx");
    assertEquals(2, respuesta.versionNo(), "la versión nueva se conserva");
    assertEquals("CEDULA VIEJA 111", respuesta.textoExtraido());
    assertEquals("CEDULA VIEJA 111", texto(CASO.toString(), CEDULA));
    assertEquals(
        "=== DOCUMENTO: CEDULA ===\nCEDULA VIEJA 111", texto(CASO.toString(), "consolidado"));
    assertThat(cache.listResults(CASO.toString()))
        .singleElement()
        .satisfies(r -> assertEquals("CEDULA VIEJA 111", r.textoExtraido()));
    verify(carga, never()).guardarResultadoOcr(anyString(), any());
  }

  @Test
  void conFilasPorDocumentoElConsolidadoNoSeEnviaYCadaDocumentoVaUnaVez() {
    filaPrevia(CASO.toString(), CEDULA, "CEDULA", "CEDULA 0912345678");
    filaPrevia(CASO.toString(), AVALUO, "AVALUO", "AVALUO USD 95000");
    filaPrevia(
        CASO.toString(),
        "consolidado",
        "CONSOLIDADO",
        "=== DOCUMENTO: CEDULA ===\nCEDULA 0912345678\n\n=== DOCUMENTO: AVALUO ===\nAVALUO USD 95000");

    String enviado = textoEnviadoAlLlm(new AnalysisRequestDTO(false, null));

    assertEquals(1, veces(enviado, "CEDULA 0912345678"));
    assertEquals(1, veces(enviado, "AVALUO USD 95000"));
    assertThat(enviado).doesNotContain("id=\"consolidado\"");
  }

  @Test
  void soloConConsolidadoSeUsaElConsolidado() {
    filaPrevia(CASO.toString(), "consolidado", "CONSOLIDADO", "TEXTO UNIDO DEL WORKER");

    assertThat(textoEnviadoAlLlm(new AnalysisRequestDTO(false, null)))
        .contains("TEXTO UNIDO DEL WORKER");
  }

  @Test
  void expedientePersistidoLeeSoloPorCaseIdAunqueVengaElSessionIdDelBorrador() {
    filaPrevia(BORRADOR.toString(), "DOC-AB12CD34", "CEDULA", "CEDULA DEL BORRADOR");
    cache.putResult(
        BORRADOR.toString(),
        new OcrFileResult("DOC-AB12CD34", "c.pdf", "CEDULA", true, null, 0.9, "CEDULA DEL BORRADOR"));
    filaPrevia(CASO.toString(), CEDULA, "CEDULA", "CEDULA DEL CASO");

    String enviado = textoEnviadoAlLlm(new AnalysisRequestDTO(false, BORRADOR.toString()));

    assertThat(enviado).contains("CEDULA DEL CASO").doesNotContain("DOC-AB12CD34");
    assertThat(enviado).doesNotContain("CEDULA DEL BORRADOR");
  }

  @Test
  void documentosReemplazadosSeNombranEnRegularizacionPendiente() {
    filaPrevia(CASO.toString(), CEDULA, "CEDULA", "CEDULA NUEVA 222");
    filaPrevia(CASO.toString(), AVALUO, "AVALUO", "AVALUO USD 95000");

    String enviado =
        textoEnviadoAlLlm(
            new AnalysisRequestDTO(false, null, List.of(CEDULA, UUID.randomUUID().toString())));

    String bloque = enviado.substring(enviado.indexOf("<regularizacion_pendiente>"));
    assertThat(bloque)
        .contains("REEMPLAZADOS")
        .contains("id=\"" + CEDULA + "\"")
        .contains("tipo=\"CEDULA\"")
        .contains("Reevalúa")
        .doesNotContain(AVALUO);
  }

  @Test
  void sinDocumentosReemplazadosNiPendientesNoHayBloque() {
    filaPrevia(CASO.toString(), CEDULA, "CEDULA", "CEDULA 0912345678");

    assertThat(textoEnviadoAlLlm(new AnalysisRequestDTO(false, null, List.of(UUID.randomUUID().toString()))))
        .doesNotContain("<regularizacion_pendiente>");
  }

  @Test
  void bloqueConPendientesYReemplazadosConservaLasReglasDeRegularizacion() {
    String bloque =
        IaAnalysisService.contextoRegularizacion(
            List.of(new ObservacionesCotejo.Entrada("CED_VENCIDA", "HIGH", "Cédula vencida")),
            new ArrayList<>(
                List.of(new com.lexia.api.modules.ia.ocr.OcrExpedienteTexto.DocOcr(CEDULA, "CEDULA", null, "x"))));

    assertThat(bloque)
        .contains("id=\"" + CEDULA + "\"")
        .contains("[RESUELTA] CODIGO")
        .contains("[HIGH] CED_VENCIDA: Cédula vencida");
  }
}
