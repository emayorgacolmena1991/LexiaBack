package com.lexia.api.modules.expedientes.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.lexia.api.modules.expedientes.caso.CaseActionRepository;
import com.lexia.api.modules.expedientes.caso.CaseExceptionRepository;
import com.lexia.api.modules.expedientes.caso.CaseValidationRepository;
import com.lexia.api.modules.expedientes.caso.ExpedienteSeguimientoService;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Modo;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Resultado;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.DocumentoRequisitoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ClasificacionDocumento;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Wizard: el documento del borrador se clasifica igual que en un expediente y la promoción copia
 * tipo y clasificación al LegalDocument sin reclasificar. Servicios reales; solo los repositorios
 * y el LLM son dobles.
 */
class ClasificacionBorradorTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DRAFT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();

  @TempDir Path almacenamiento;

  private final AnalisisDocumentoService llm = mock(AnalisisDocumentoService.class);
  private final Map<UUID, LegalDocument> guardados = new LinkedHashMap<>();
  private CargaDocumentoService carga;
  private ClasificacionDocumentoService clasificador;
  private ExpedienteSeguimientoService seguimiento;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));

    LegalCase legalCase = mock(LegalCase.class);
    when(legalCase.getId()).thenReturn(CASO);
    when(legalCase.getIngestionMode()).thenReturn("DIGITAL_SEPARADO");
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));

    LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
    when(documents.save(any()))
        .thenAnswer(
            llamada -> {
              LegalDocument doc = llamada.getArgument(0);
              guardados.put(doc.getId(), doc);
              return doc;
            });
    when(documents.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
        .thenAnswer(llamada -> Optional.ofNullable(guardados.get(llamada.<UUID>getArgument(0))));
    when(documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, TENANT))
        .thenAnswer(llamada -> new ArrayList<>(guardados.values()));
    DocumentoTextoOcrRepository ocrRows = mock(DocumentoTextoOcrRepository.class);
    when(ocrRows.findByIdExpedienteAndIdDocumento(anyString(), anyString())).thenReturn(Optional.empty());

    CaseDocumentoStore caseDocs =
        new CaseDocumentoStore(
            legalCases,
            mock(WritingFileRepository.class),
            documents,
            mock(DocumentVersionRepository.class),
            ocrRows,
            new ExpedienteDocumentoStorage(new ArchivoStorageLocal(almacenamiento)),
            mock(ObjectProvider.class));

    ExpedienteBorradorStore borradores = mock(ExpedienteBorradorStore.class);
    when(borradores.find(DRAFT, TENANT))
        .thenReturn(
            Optional.of(
                ExpedienteBorrador.loaded(
                    DRAFT, TENANT, null, "VIV_HIPOTECADA_BIESS", "GUAYAQUIL", "DIGITAL_SEPARADO")));
    ProductoBiessService productos = mock(ProductoBiessService.class);
    when(productos.detalle("VIV_HIPOTECADA_BIESS", "GUAYAQUIL"))
        .thenReturn(
            new ProductoDetalle(
                "VIV_HIPOTECADA_BIESS",
                "BIESS",
                List.of(
                    new DocumentoRequisitoItem("CEDULA", "Cédula", true, 0, null, 1),
                    new DocumentoRequisitoItem("AVALUO", "Avalúo", true, 0, null, 2)),
                List.of()));

    carga =
        new CargaDocumentoService(
            mock(ActoNotarialService.class),
            null,
            mock(DocumentoTextoOcrRepository.class),
            productos,
            borradores,
            caseDocs,
            mock(ObjectProvider.class));
    ObjectProvider<AnalisisDocumentoService> proveedor = mock(ObjectProvider.class);
    doReturn(llm).when(proveedor).getIfAvailable();
    clasificador = new ClasificacionDocumentoService(caseDocs, carga, proveedor, 0.8, 5000);
    seguimiento =
        new ExpedienteSeguimientoService(
            legalCases,
            caseDocs,
            carga,
            mock(CaseValidationRepository.class),
            mock(CaseExceptionRepository.class),
            mock(CaseActionRepository.class),
            mock(AuditEventRepository.class),
            mock(AppUserRepository.class),
            mock(AuthorizationService.class));
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  private String subir(String nombre) {
    MockMultipartFile pdf =
        new MockMultipartFile(
            "file", nombre, "application/pdf", "%PDF-1.4 contenido".getBytes(StandardCharsets.UTF_8));
    return carga.subir(DRAFT.toString(), pdf).idDocumento();
  }

  private void llmResponde(String tipo, double confianza) {
    when(llm.clasificarDocumento(anyString(), any(), any()))
        .thenReturn(new ClasificacionDocumento(tipo, confianza, "claude-test"));
  }

  private Resultado clasificar(String docId) {
    return clasificador.clasificar(DRAFT.toString(), docId, "CEDULA DE CIUDADANIA 0912345678", Modo.ASIGNAR);
  }

  private DocumentoCargadoDTO enElWizard(String docId) {
    return carga.listarDocumentos(DRAFT.toString()).stream()
        .filter(d -> d.idDocumento().equals(docId))
        .findFirst()
        .orElseThrow();
  }

  private LegalDocument promover() {
    seguimiento.vincularBorrador(DRAFT.toString(), CASO);
    assertThat(guardados).hasSize(1);
    return guardados.values().iterator().next();
  }

  @Test
  void borradorClasificadoSeVeEnElWizardYAlPromoverConservaTipoYOrigenAuto() {
    String docId = subir("cedula.pdf");
    llmResponde("CEDULA", 0.95);

    assertEquals("CEDULA", clasificar(docId).tipoAsignado());
    DocumentoCargadoDTO wizard = enElWizard(docId);
    assertEquals("CEDULA", wizard.tipoDocumento());
    assertEquals("CEDULA", wizard.tipoSugerido());
    assertEquals(LegalDocument.ESTADO_CLASIFICADO, wizard.clasificacionEstado());
    assertEquals(LegalDocument.ORIGEN_AUTO, wizard.clasificacionOrigen());

    LegalDocument promovido = promover();

    assertEquals("CEDULA", promovido.getDocType());
    assertEquals(LegalDocument.ORIGEN_AUTO, promovido.getClasificacionOrigen());
    assertEquals(LegalDocument.ESTADO_CLASIFICADO, promovido.getClasificacionEstado());
    assertEquals(new BigDecimal("0.950"), promovido.getClasificacionConfianza());
    assertEquals("claude-test", promovido.getClasificacionModelo());
    verify(llm, times(1)).clasificarDocumento(anyString(), any(), any());
  }

  @Test
  void tipoCambiadoEnElWizardQuedaManualTrasPromover() {
    String docId = subir("cedula.pdf");
    llmResponde("CEDULA", 0.95);
    clasificar(docId);

    carga.actualizarTipo(DRAFT.toString(), docId, new ActualizarTipoRequest("AVALUO"));
    assertEquals(LegalDocument.ORIGEN_MANUAL, enElWizard(docId).clasificacionOrigen());

    LegalDocument promovido = promover();

    assertEquals("AVALUO", promovido.getDocType());
    assertEquals(LegalDocument.ORIGEN_MANUAL, promovido.getClasificacionOrigen());
    assertEquals("CEDULA", promovido.getTipoSugerido(), "conserva la última sugerencia");
    assertEquals(true, promovido.tipoFijadoPorUsuario());
  }

  @Test
  void confianzaBajaEnBorradorQuedaSugeridaYAsiPasaAlExpediente() {
    String docId = subir("escaneo.pdf");
    llmResponde("AVALUO", 0.4);

    assertNull(clasificar(docId).tipoAsignado());
    DocumentoCargadoDTO wizard = enElWizard(docId);
    assertNull(wizard.tipoDocumento());
    assertEquals("AVALUO", wizard.tipoSugerido());
    assertEquals(LegalDocument.ESTADO_POR_REVISAR, wizard.clasificacionEstado());

    LegalDocument promovido = promover();

    assertNull(promovido.getDocType());
    assertEquals("AVALUO", promovido.getTipoSugerido());
    assertEquals(LegalDocument.ESTADO_POR_REVISAR, promovido.getClasificacionEstado());
    assertNull(promovido.getClasificacionOrigen());
  }

  @Test
  void tipoElegidoEnElWizardAntesDelOcrNoSeReclasifica() {
    String docId = subir("cedula.pdf");
    carga.actualizarTipo(DRAFT.toString(), docId, new ActualizarTipoRequest("CEDULA"));

    assertFalse(clasificar(docId).ejecutado());
    verify(llm, never()).clasificarDocumento(anyString(), any(), any());
    assertEquals(LegalDocument.ORIGEN_MANUAL, promover().getClasificacionOrigen());
  }

  @Test
  void falloDelLlmEnBorradorQuedaEnError() {
    String docId = subir("cedula.pdf");
    when(llm.clasificarDocumento(anyString(), any(), any())).thenThrow(new IllegalStateException("caído"));

    assertEquals(LegalDocument.ESTADO_ERROR, clasificar(docId).estado());
    assertEquals(LegalDocument.ESTADO_ERROR, enElWizard(docId).clasificacionEstado());
    assertNull(enElWizard(docId).tipoDocumento());
  }
}
