package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionService.GenerarResultado;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.PlantillaDatos;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage.StoredFile;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CoactivaActuacionGenerarIncompletoTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final String RUTA = "templates/coactivas/FORMATO_PROV_EMBARGO_TEMPLATE.docx";
  private static final String FALTANTE = "nombre_garante_solidario";

  @Mock private AuthorizationService authorization;
  @Mock private CoactivaExpedienteService expedientes;
  @Mock private CoactivaPlantillaRepository plantillas;
  @Mock private CoactivaActuacionRepository actuaciones;
  @Mock private CoactivaArchivoRepository archivos;
  @Mock private CoactivaArchivoStorage storage;
  @Mock private CoactivaPlantillaDataMapper mapper;

  private final DocxPdfConverter pdfConverter = spy(new DocxPdfConverter("", "target/coactivas-test"));
  private CoactivaActuacionService service;
  private CoactivaExpediente expediente;
  private CoactivaPlantilla plantilla;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USER, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    service =
        new CoactivaActuacionService(
            authorization, expedientes, plantillas, actuaciones, null, null, archivos, storage, mapper,
            new DocxMinutaRenderer(), pdfConverter);
    expediente = CoactivaPlantillaDataMapperTest.expediente();
    expediente.confirmarEtapa("EMBARGO", USER);
    plantilla = new CoactivaPlantilla();
    ReflectionTestUtils.setField(plantilla, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(plantilla, "nombre", "Providencia de embargo.docx");
    ReflectionTestUtils.setField(plantilla, "etapa", "EMBARGO");
    ReflectionTestUtils.setField(plantilla, "activo", true);
    ReflectionTestUtils.setField(plantilla, "rutaDocx", RUTA);

    when(expedientes.require(TENANT, expediente.getId())).thenReturn(expediente);
    when(plantillas.findByIdAndTenantIdAndActivoTrue(plantilla.getId(), TENANT)).thenReturn(Optional.of(plantilla));
    when(mapper.mapear(expediente)).thenReturn(datosSinGarante());
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void porDefectoConFaltantesRespondePlantillaDatosIncompletos() {
    ApiException e =
        assertThrows(
            ApiException.class, () -> service.generar(expediente.getId(), plantilla.getId(), null, false));

    assertEquals(CoactivaActuacionService.PLANTILLA_DATOS_INCOMPLETOS, e.getCode());
    assertEquals(422, e.getStatus().value());
    assertEquals(List.of(FALTANTE), e.getDetails().get("variablesFaltantes"));
    verify(storage, never()).storeBytes(any(), any(), any(), any());
    verify(actuaciones, never()).save(any());
  }

  @Test
  void confirmadoGeneraPdfYRegistraArchivoYActuacion() {
    stubPersistencia();

    GenerarResultado r = service.generar(expediente.getId(), plantilla.getId(), null, true);

    assertTrue(r.creada());
    assertNotNull(r.body().archivoId());
    verify(storage).storeBytes(eq(TENANT), eq(r.body().archivoId()), eq("Providencia de embargo.pdf"), any());
    verify(archivos).save(any());
    verify(actuaciones).save(any());
    ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
    verify(expedientes)
        .registrarEvento(
            eq(TENANT), eq(expediente.getId()), eq("ACTUACION"), anyString(), detalle.capture(), eq(USER),
            eq(r.body().archivoId()));
    assertTrue(detalle.getValue().contains("campos vacíos: " + FALTANTE));
  }

  @Test
  void pdfIncompletoConservaLosDatosEncontradosYNoDejaPlaceholders() throws Exception {
    stubPersistencia();

    service.generar(expediente.getId(), plantilla.getId(), null, true);

    ArgumentCaptor<byte[]> docx = ArgumentCaptor.forClass(byte[].class);
    verify(pdfConverter).toPdf(docx.capture());
    String texto;
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx.getValue()));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      texto = extractor.getText();
    }
    assertFalse(texto.contains("{{"));
    assertFalse(texto.contains("}}"));
    assertFalse(texto.contains("PEDRO GARANTE"));
    assertTrue(texto.contains("JUAN CARLOS PEREZ LOPEZ"));
    assertTrue(texto.contains("USD $1,250.50 (MIL DOSCIENTOS CINCUENTA CON 50/100"));
    assertTrue(texto.contains("juan.perez@mail.com"));

    ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
    verify(storage).storeBytes(eq(TENANT), any(), any(), pdf.capture());
    assertEquals("%PDF", new String(pdf.getValue(), 0, 4));
  }

  private void stubPersistencia() {
    when(storage.storeBytes(eq(TENANT), any(), any(), any()))
        .thenAnswer(i -> new StoredFile("ruta", "sha", ((byte[]) i.getArgument(3)).length));
    when(actuaciones.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  private static PlantillaDatos datosSinGarante() {
    PlantillaDatos completos =
        CoactivaPlantillaDataMapper.construir(
            CoactivaPlantillaDataMapperTest.fuentes(CoactivaPlantillaDataMapperTest.ocrCompleto()),
            CoactivaPlantillaDataMapperTest.AHORA);
    Map<String, Object> valores = new HashMap<>(completos.valores());
    valores.remove(FALTANTE);
    return new PlantillaDatos(valores, completos.discrepancias(), true);
  }
}
