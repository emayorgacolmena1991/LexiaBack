package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.common.storage.ArchivoStorageLocal;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.BorradorPromocionService;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.CrearMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.PreviewMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.core.MinutaDraft;
import com.lexia.api.modules.expedientes.escrituracion.core.MinutaDraftRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.expedientes.proceso.ProductTemplate;
import com.lexia.api.modules.expedientes.proceso.ProductTemplateRepository;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.prompt.ActoVariableCatalog;
import com.lexia.api.modules.ia.prompt.ActoVariableCatalog.ActoVariableSchema;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MinutaGenerationOverridesTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final String PRODUCTO = MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS;
  private static final String TIPO = "MINUTA_COMPRAVENTA";
  private static final String VENDEDOR = "VENDEDORA CORREGIDA A MANO S.A.";

  @TempDir Path storage;

  private final ObjectMapper mapper = new ObjectMapper();
  private final MinutaDraftRepository drafts = mock(MinutaDraftRepository.class);
  private final DatosBiessStore datosBiess = mock(DatosBiessStore.class);
  private final List<MinutaDraft> guardados = new ArrayList<>();
  private MinutaGenerationService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));

    LegalCase legalCase = mock(LegalCase.class);
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT))
        .thenReturn(Optional.of(legalCase));
    WritingFile file = mock(WritingFile.class);
    when(file.getId()).thenReturn(UUID.randomUUID());
    when(file.getProductCode()).thenReturn(PRODUCTO);
    WritingFileRepository writingFiles = mock(WritingFileRepository.class);
    when(writingFiles.findByCaseIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT))
        .thenReturn(Optional.of(file));
    ProductTemplate template = mock(ProductTemplate.class);
    when(template.getTemplateKind()).thenReturn(TIPO);
    ProductTemplateRepository templates = mock(ProductTemplateRepository.class);
    when(templates.findByTenantIdAndProductCodeAndActiveTrueOrderBySortOrderAsc(TENANT, PRODUCTO))
        .thenReturn(List.of(template));
    when(drafts.save(any()))
        .thenAnswer(
            call -> {
              MinutaDraft draft = call.getArgument(0);
              if (!guardados.contains(draft)) {
                guardados.add(draft);
              }
              return draft;
            });
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(any(), eq(TENANT)))
        .thenAnswer(call -> List.copyOf(guardados));
    BorradorPromocionService promocion = mock(BorradorPromocionService.class);
    when(promocion.asegurarExpediente(CASO)).thenReturn(CASO);
    ExtractedDataRepository extractedData = mock(ExtractedDataRepository.class);
    when(extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(CASO, TENANT))
        .thenReturn(List.of());
    DocxPdfConverter pdf = mock(DocxPdfConverter.class);
    when(pdf.toPdf(any())).thenReturn(new byte[] {1});
    ActoVariableCatalog actos = mock(ActoVariableCatalog.class);
    when(actos.resolve(anyString(), anyString()))
        .thenReturn(
            new ActoVariableSchema(
                "ACTO",
                PRODUCTO,
                TIPO,
                List.of(
                    "nombre_vendedor",
                    "cedula_vendedor",
                    "nombre_conyuge_1",
                    "cedula_conyuge_1",
                    "descripcion_inmueble_hipoteca",
                    "canton_inmueble",
                    "monto_prestamo")));

    DocxMinutaRenderer renderer = new DocxMinutaRenderer();
    service =
        new MinutaGenerationService(
            mock(AuthorizationService.class),
            legalCases,
            writingFiles,
            drafts,
            templates,
            mock(OcrSessionCacheService.class),
            mock(AnalisisDocumentoService.class),
            new MinutaTemplateCatalog(),
            renderer,
            datosBiess,
            extractedData,
            mapper,
            new ArchivoStorageLocal(storage.resolve("data"), storage),
            promocion,
            mock(MinutaDocxContenido.class),
            new ExpedienteVariablesService(actos, renderer, mapper),
            pdf);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  private static String texto(Path docx) throws Exception {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(Files.readAllBytes(docx)));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      return extractor.getText();
    }
  }

  private MinutaDraft draftConArchivo(UUID tenant) throws Exception {
    Path docx = Files.write(storage.resolve(UUID.randomUUID() + ".docx"), new byte[] {7, 8});
    MinutaDraft draft = MinutaDraft.create(tenant, UUID.randomUUID(), PRODUCTO, TIPO);
    draft.markGenerated(docx.toString());
    return draft;
  }

  /** {@code draftConArchivo} guarda una ruta absoluta bajo la carpeta heredada de minutas. */
  @Test
  void leerDocxSirveBorradoresDelTenantDeSesion() throws Exception {
    assertArrayEquals(new byte[] {7, 8}, service.leerDocx(draftConArchivo(TENANT)));
  }

  @Test
  void leerDocxRechazaBorradorDeOtroTenant() throws Exception {
    MinutaDraft ajeno = draftConArchivo(UUID.randomUUID());
    assertThrows(ApiException.class, () -> service.leerDocx(ajeno));
  }

  @Test
  void generarDosVecesLaMismaMinutaGuardaDosClavesRelativasConTenant() throws Exception {
    PreviewMinutaRequest request = new PreviewMinutaRequest(Map.of("nombre_vendedor", VENDEDOR), null);
    service.previsualizar(CASO, TIPO, request);
    MinutaDraft draft = guardados.get(0);
    String primera = draft.getStoragePath();

    service.previsualizar(CASO, TIPO, request);
    String segunda = draft.getStoragePath();

    assertEquals(1, guardados.size(), "mismo borrador");
    assertNotEquals(primera, segunda);
    for (String clave : List.of(primera, segunda)) {
      assertTrue(clave.startsWith(TENANT + "/minutas/" + draft.getId() + "_"), clave);
      assertTrue(Files.isRegularFile(storage.resolve("data").resolve(clave)), "nunca sobrescribe");
    }
    assertTrue(texto(storage.resolve("data").resolve(segunda)).contains(VENDEDOR));
  }

  @Test
  void nombreGuardadoQuitaElPrefijoDeLaClaveYDeLaRutaHeredada() {
    MinutaDraft draft = MinutaDraft.create(TENANT, UUID.randomUUID(), PRODUCTO, TIPO);
    draft.markGenerated(TENANT + "/minutas/" + draft.getId() + "_" + UUID.randomUUID() + "_minuta_compraventa.docx");
    assertEquals("minuta_compraventa.docx", MinutaGenerationService.nombreGuardado(draft));
    draft.markGenerated("C:\\lexia\\data\\minutas\\" + draft.getId() + "_minuta_compraventa.docx");
    assertEquals("minuta_compraventa.docx", MinutaGenerationService.nombreGuardado(draft));
  }

  @Test
  void generarConservaLosOverridesDePrevisualizar() throws Exception {
    service.previsualizar(
        CASO,
        TIPO,
        new PreviewMinutaRequest(
            Map.of(
                "nombre_vendedor", VENDEDOR,
                "cedula_vendedor", "0912345678",
                "nombre_conyuge_1", "ANA PEREZ",
                "cedula_conyuge_1", "0987654321",
                "descripcion_inmueble_hipoteca", "SOLAR 13",
                "canton_inmueble", "DAULE",
                "monto_prestamo", "90000"),
            null));

    service.generar(CASO, new CrearMinutaRequest(TIPO, null));

    assertEquals(2, guardados.size());
    MinutaDraft nuevo = guardados.get(1);
    assertNotEquals(guardados.get(0).getId(), nuevo.getId());
    assertTrue(texto(storage.resolve("data").resolve(nuevo.getStoragePath())).contains(VENDEDOR));
    JsonNode payload = mapper.readTree(nuevo.getPayload());
    assertEquals(VENDEDOR, payload.path("nombre_vendedor").asText());
    assertEquals(VENDEDOR, mapper.readTree(nuevo.getOverrides()).path("nombre_vendedor").asText());
    assertEquals("", mapper.readTree(nuevo.getDatosExtraidos()).path("nombre_vendedor").asText());

    verify(datosBiess, never()).guardar(any(), any(), any());
  }
}
