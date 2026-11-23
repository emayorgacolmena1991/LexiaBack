package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.TipoPermitidoDTO;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Modo;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Resultado;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ClasificacionDocumento;
import com.lexia.api.modules.ia.llm.LlmTimeoutException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ClasificacionDocumentoServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final List<String> CATALOGO = List.of("CEDULA", "AVALUO");

  private final LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
  private final LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
  private final DocumentoTextoOcrRepository ocrRows = mock(DocumentoTextoOcrRepository.class);
  private final CargaDocumentoService carga = mock(CargaDocumentoService.class);
  private final AnalisisDocumentoService llm = mock(AnalisisDocumentoService.class);
  private final LegalCase legalCase = mock(LegalCase.class);
  private ClasificacionDocumentoService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    when(legalCase.getId()).thenReturn(CASO);
    when(legalCase.getIngestionMode()).thenReturn("DIGITAL_SEPARADO");
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));
    when(ocrRows.findByIdExpedienteAndIdDocumento(anyString(), anyString())).thenReturn(Optional.empty());
    when(carga.tiposPermitidos(CASO.toString()))
        .thenReturn(CATALOGO.stream().map(c -> new TipoPermitidoDTO(c, c)).toList());
    CaseDocumentoStore store =
        new CaseDocumentoStore(
            legalCases,
            mock(WritingFileRepository.class),
            documents,
            mock(DocumentVersionRepository.class),
            ocrRows,
            null,
            mock(ObjectProvider.class));
    ObjectProvider<AnalisisDocumentoService> proveedor = mock(ObjectProvider.class);
    doReturn(llm).when(proveedor).getIfAvailable();
    service = new ClasificacionDocumentoService(store, carga, proveedor, 0.8, 5000);
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  private LegalDocument documento(String tipo) {
    LegalDocument doc = LegalDocument.create(TENANT, CASO, "archivo.pdf", tipo);
    when(documents.findByIdAndTenantIdAndDeletedAtIsNull(doc.getId(), TENANT)).thenReturn(Optional.of(doc));
    return doc;
  }

  private void llmResponde(String tipo, double confianza) {
    when(llm.clasificarDocumento(anyString(), eq(CATALOGO), any()))
        .thenReturn(new ClasificacionDocumento(tipo, confianza, "claude-test"));
  }

  private Resultado clasificar(LegalDocument doc, Modo modo) {
    return service.clasificar(CASO.toString(), doc.getId().toString(), "CEDULA DE CIUDADANIA 0912345678", modo);
  }

  @Test
  void confianzaAltaAsignaElTipoComoAuto() {
    LegalDocument doc = documento(null);
    llmResponde("CEDULA", 0.93);

    Resultado r = clasificar(doc, Modo.ASIGNAR);

    assertEquals("CEDULA", r.tipoAsignado());
    assertEquals("CEDULA", doc.getDocType());
    assertEquals(LegalDocument.ORIGEN_AUTO, doc.getClasificacionOrigen());
    assertEquals(LegalDocument.ESTADO_CLASIFICADO, doc.getClasificacionEstado());
    assertEquals(new BigDecimal("0.930"), doc.getClasificacionConfianza());
    assertEquals("claude-test", doc.getClasificacionModelo());
  }

  @Test
  void confianzaBajaQuedaPorRevisarConSugerido() {
    LegalDocument doc = documento(null);
    llmResponde("AVALUO", 0.55);

    Resultado r = clasificar(doc, Modo.ASIGNAR);

    assertNull(r.tipoAsignado());
    assertNull(doc.getDocType());
    assertEquals("AVALUO", doc.getTipoSugerido());
    assertEquals(LegalDocument.ESTADO_POR_REVISAR, doc.getClasificacionEstado());
  }

  @Test
  void manualNoSePisaNiSeLlamaAlLlm() {
    LegalDocument doc = documento(null);
    doc.classify("AVALUO");

    Resultado r = clasificar(doc, Modo.ASIGNAR);

    assertFalse(r.ejecutado());
    assertEquals("AVALUO", doc.getDocType());
    assertEquals(LegalDocument.ORIGEN_MANUAL, doc.getClasificacionOrigen());
    verify(llm, never()).clasificarDocumento(anyString(), any(), any());
  }

  @Test
  void manualPuestoDuranteLaLlamadaTampocoSePisa() {
    LegalDocument doc = documento(null);
    when(llm.clasificarDocumento(anyString(), eq(CATALOGO), any()))
        .thenAnswer(
            llamada -> {
              doc.classify("AVALUO");
              return new ClasificacionDocumento("CEDULA", 0.99, "claude-test");
            });

    Resultado r = clasificar(doc, Modo.ASIGNAR);

    assertNull(r.tipoAsignado());
    assertEquals("AVALUO", doc.getDocType());
    assertEquals(LegalDocument.ORIGEN_MANUAL, doc.getClasificacionOrigen());
  }

  @Test
  void tipoPrevioSinOrigenCuentaComoDelUsuario() {
    LegalDocument doc = documento("AVALUO");

    assertFalse(clasificar(doc, Modo.ASIGNAR).ejecutado());
    assertEquals("AVALUO", doc.getDocType());
  }

  @Test
  void respuestaFueraDeLaListaEsOtroYNoSeAsigna() {
    LegalDocument doc = documento(null);
    llmResponde("PASAPORTE", 0.97);

    Resultado r = clasificar(doc, Modo.ASIGNAR);

    assertEquals(ClasificacionDocumentoService.OTRO, r.tipoDetectado());
    assertNull(doc.getDocType());
    assertEquals(ClasificacionDocumentoService.OTRO, doc.getTipoSugerido());
    assertEquals(LegalDocument.ESTADO_POR_REVISAR, doc.getClasificacionEstado());
  }

  @Test
  void falloDelLlmQuedaEnErrorSinLanzar() {
    LegalDocument doc = documento(null);
    when(llm.clasificarDocumento(anyString(), any(), any()))
        .thenThrow(new LlmTimeoutException("Claude no respondió en 5 segundos.", null));

    Resultado r = clasificar(doc, Modo.ASIGNAR);

    assertEquals(LegalDocument.ESTADO_ERROR, r.estado());
    assertEquals(LegalDocument.ESTADO_ERROR, doc.getClasificacionEstado());
    assertNull(doc.getDocType());
  }

  @Test
  void enReemplazoSoloDetectaYMarcaPorRevisarSiNoCoincide() {
    LegalDocument doc = documento(null);
    doc.classify("CEDULA");
    llmResponde("AVALUO", 0.95);

    Resultado r = clasificar(doc, Modo.SOLO_DETECTAR);

    assertEquals("AVALUO", r.tipoDetectado());
    assertNull(r.tipoAsignado());
    assertEquals("CEDULA", doc.getDocType(), "el documento conserva su tipo");
    assertEquals(LegalDocument.ORIGEN_MANUAL, doc.getClasificacionOrigen());
    assertEquals(LegalDocument.ESTADO_POR_REVISAR, doc.getClasificacionEstado());
  }

  @Test
  void alLlmVanSoloLosPrimeros4000CaracteresConElTimeoutConfigurado() {
    LegalDocument doc = documento(null);
    llmResponde("CEDULA", 0.9);

    service.clasificar(CASO.toString(), doc.getId().toString(), "x".repeat(9000), Modo.ASIGNAR);

    ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
    verify(llm).clasificarDocumento(texto.capture(), eq(CATALOGO), eq(Duration.ofMillis(5000)));
    assertEquals(4000, texto.getValue().length());
  }

  @Test
  void borradorYEscaneadoNoSeClasifican() {
    assertFalse(
        service.clasificar(UUID.randomUUID().toString(), "DOC-AB12CD34", "texto", Modo.ASIGNAR).ejecutado());
    when(legalCase.getIngestionMode()).thenReturn("FISICO_ESCANEADO");
    LegalDocument doc = documento(null);
    assertFalse(clasificar(doc, Modo.ASIGNAR).ejecutado());
    verify(llm, never()).clasificarDocumento(anyString(), any(), any());
  }

  @Test
  void confianzaFueraDeRangoSeAcota() {
    assertEquals(new BigDecimal("1.000"), ClasificacionDocumentoService.confianza(7));
    assertEquals(new BigDecimal("0.000"), ClasificacionDocumentoService.confianza(-1));
    assertEquals(new BigDecimal("0.000"), ClasificacionDocumentoService.confianza(Double.NaN));
    assertTrue(ClasificacionDocumentoService.normalizar(" CEDULA ", CATALOGO).equals("CEDULA"));
  }
}
