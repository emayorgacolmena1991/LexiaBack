package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.ExpedienteDocumentoStorage;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import com.lexia.api.modules.expedientes.minutas.MinutaTemplateCatalog;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class NotaryEscrituraServiceTest {

  private static final UUID FIRMA = UUID.randomUUID();
  private static final UUID NOTARIA = UUID.randomUUID();
  private static final UUID OTRA_NOTARIA = UUID.randomUUID();
  private static final UUID USUARIO = UUID.randomUUID();
  private static final String PRODUCTO = MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS;
  private static final String TITULAR = "Juan Carlos Ramírez";

  @TempDir Path almacenamiento;

  private final ObjectMapper mapper = new ObjectMapper();
  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final NotaryRemisionRepository remisiones = mock(NotaryRemisionRepository.class);
  private final NotaryRemisionVersionRepository versiones =
      mock(NotaryRemisionVersionRepository.class);
  private final NotaryEscrituraRepository escrituras = mock(NotaryEscrituraRepository.class);
  private final NotaryTenantProfileRepository perfiles = mock(NotaryTenantProfileRepository.class);
  private final Map<UUID, NotaryRemision> remisionesGuardadas = new HashMap<>();
  private final List<NotaryRemisionVersion> versionesGuardadas = new ArrayList<>();
  private final List<NotaryEscritura> escriturasGuardadas = new ArrayList<>();
  private final Map<UUID, NotaryTenantProfile> perfilesGuardados = new HashMap<>();
  private ExpedienteDocumentoStorage storage;
  private NotaryLogoService logos;
  private NotaryEscrituraService service;

  @BeforeEach
  void setUp() {
    perfilesGuardados.put(NOTARIA, perfil("12"));
    when(perfiles.findById(any()))
        .thenAnswer(
            llamada -> Optional.ofNullable(perfilesGuardados.get(llamada.<UUID>getArgument(0))));
    when(remisiones.findById(any()))
        .thenAnswer(
            llamada -> Optional.ofNullable(remisionesGuardadas.get(llamada.<UUID>getArgument(0))));
    when(versiones.findFirstByRemisionIdOrderByVersionNoDesc(any()))
        .thenAnswer(
            llamada ->
                versionesGuardadas.stream()
                    .filter(v -> v.getRemisionId().equals(llamada.getArgument(0)))
                    .reduce((anterior, siguiente) -> siguiente));
    when(escrituras.save(any()))
        .thenAnswer(
            llamada -> {
              escriturasGuardadas.add(llamada.getArgument(0));
              return llamada.getArgument(0);
            });
    when(escrituras.findByRemisionIdOrderByGeneratedAtDesc(any()))
        .thenAnswer(
            llamada ->
                escriturasGuardadas.stream()
                    .filter(e -> e.getRemisionId().equals(llamada.getArgument(0)))
                    .toList());
    when(escrituras.findByIdAndRemisionId(any(), any()))
        .thenAnswer(
            llamada ->
                escriturasGuardadas.stream()
                    .filter(e -> e.getId().equals(llamada.getArgument(0)))
                    .filter(e -> e.getRemisionId().equals(llamada.getArgument(1)))
                    .findFirst());

    storage = new ExpedienteDocumentoStorage(almacenamiento.toString());
    AuditEventRepository auditoria = mock(AuditEventRepository.class);
    logos = new NotaryLogoService(authorization, perfiles, storage, auditoria);
    service =
        new NotaryEscrituraService(
            authorization,
            remisiones,
            versiones,
            escrituras,
            perfiles,
            logos,
            new DocxMinutaRenderer(),
            new DocxPdfConverter("", "target/notary-escritura-test"),
            new MinutaTemplateCatalog(),
            storage,
            mock(AppUserRepository.class),
            auditoria,
            mapper);
    sesion(NOTARIA);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  private static NotaryTenantProfile perfil(String numero) {
    return NotaryTenantProfile.create(
        NOTARIA, "1790000000001", "Quito", numero, "Notaría Duodécima de Quito", TITULAR, null, true);
  }

  private static void sesion(UUID tenant) {
    AuthContext.set(new AuthPrincipal(USUARIO, UUID.randomUUID(), tenant, UUID.randomUUID()));
  }

  /** JSON de MinutaViviendaData con todas sus claves llenas. */
  private String payloadCompleto() {
    ObjectNode payload = mapper.createObjectNode();
    for (String clave : new MinutaViviendaData().toTemplateMap().keySet()) {
      payload.put(clave, "Dato " + clave);
    }
    return payload.toString();
  }

  /** Remisión en RECIBIDO con una versión del producto dado. */
  private UUID remision(String payload, String producto) {
    NotaryRemision remision =
        NotaryRemision.create(
            FIRMA, NOTARIA, UUID.randomUUID(), null, "LEX-2026-001", "Compraventa", null, 0, USUARIO, null);
    remision.acknowledgeReceipt();
    remisionesGuardadas.put(remision.getId(), remision);
    versionesGuardadas.add(
        NotaryRemisionVersion.of(
            UUID.randomUUID(),
            remision.getId(),
            1,
            payload,
            new NotaryRemisionVersion.Archivo(UUID.randomUUID(), "/m.docx", "m.docx"),
            null,
            null,
            USUARIO,
            producto));
    return remision.getId();
  }

  private UUID remisionCompleta() {
    return remision(payloadCompleto(), PRODUCTO);
  }

  private Map<String, NotaryDtos.CampoFormularioDto> campos(UUID id) {
    Map<String, NotaryDtos.CampoFormularioDto> porClave = new LinkedHashMap<>();
    service.formulario(id).campos().forEach(campo -> porClave.put(campo.clave(), campo));
    return porClave;
  }

  private NotaryDtos.EscrituraDto generarCompleta(UUID id) {
    Map<String, String> valores = new LinkedHashMap<>();
    campos(id).keySet().forEach(clave -> valores.put(clave, "Valor " + clave));
    return service.generar(id, new NotaryDtos.GenerarEscrituraBody(valores));
  }

  private byte[] docx(NotaryDtos.EscrituraDto escritura) {
    return storage.read(escrituraGuardada(escritura).getStorageRef());
  }

  private NotaryEscritura escrituraGuardada(NotaryDtos.EscrituraDto escritura) {
    return escriturasGuardadas.stream()
        .filter(e -> e.getId().equals(escritura.id()))
        .findFirst()
        .orElseThrow();
  }

  /** Partes del .docx: cuerpo, encabezados, pies y medios. */
  private static Map<String, byte[]> partes(byte[] docx) throws Exception {
    Map<String, byte[]> partes = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
      ZipEntry entrada;
      while ((entrada = zip.getNextEntry()) != null) {
        partes.put(entrada.getName(), zip.readAllBytes());
      }
    }
    return partes;
  }

  private static String textoXml(Map<String, byte[]> partes) {
    StringBuilder texto = new StringBuilder();
    partes.forEach(
        (nombre, bytes) -> {
          if (nombre.startsWith("word/") && nombre.endsWith(".xml")) {
            texto.append(new String(bytes, StandardCharsets.UTF_8).replaceAll("<[^>]+>", ""));
          }
        });
    return texto.toString();
  }

  private static void assertEstado(HttpStatus esperado, ApiException error) {
    assertEquals(esperado, error.getStatus());
  }

  @Test
  void formularioExcluyeLoCubiertoYPrellenaElPerfil() {
    UUID id = remisionCompleta();

    NotaryDtos.EscrituraFormularioDto formulario = service.formulario(id);
    Map<String, NotaryDtos.CampoFormularioDto> campos = campos(id);

    assertEquals(1, formulario.versionNo());
    assertThat(campos)
        .doesNotContainKeys(
            "nombre_vendedor",
            "clave_catastral",
            "nombre_entidad_crediticia",
            "canton_notaria",
            "logo_notaria");
    assertEquals(TITULAR, campos.get("notaria_titular_nombre").valor());
    assertEquals("DUODÉCIMA", campos.get("notaria_numero_literal").valor());
    assertEquals(NotaryEscrituraService.ORIGEN_NOTARIA, campos.get("notaria_numero_literal").origen());
    assertEquals(NotaryEscrituraService.ORIGEN_NOTARIA, campos.get("fecha_otorgamiento").origen());
    assertEquals(NotaryEscrituraService.ORIGEN_NOTARIA, campos.get("provincia_notaria").origen());
    assertEquals(NotaryEscrituraService.ORIGEN_BUFETE, campos.get("representante_vendedor").origen());
    assertEquals("Representante vendedor", campos.get("representante_vendedor").etiqueta());
    assertEquals("", campos.get("representante_vendedor").valor());
  }

  @Test
  void loQueElBufeteDejoVacioApareceComoFaltante() throws Exception {
    ObjectNode payload = (ObjectNode) mapper.readTree(payloadCompleto());
    payload.put("nombre_vendedor", "");
    UUID id = remision(payload.toString(), PRODUCTO);

    NotaryDtos.CampoFormularioDto campo = campos(id).get("nombre_vendedor");

    assertEquals(NotaryEscrituraService.ORIGEN_BUFETE, campo.origen());
    assertEquals("Nombre del vendedor", campo.etiqueta());
  }

  @Test
  void numeroDeNotariaNoNumericoSePrellenaTalCual() {
    perfilesGuardados.put(NOTARIA, perfil("12A"));
    UUID id = remisionCompleta();

    assertEquals("12A", campos(id).get("notaria_numero_literal").valor());
  }

  @Test
  void sinCantonEnElPerfilLoPideElFormulario() {
    NotaryTenantProfile sinCanton =
        NotaryTenantProfile.create(NOTARIA, "1790000000001", " ", "12", "Notaría", TITULAR, null, true);
    perfilesGuardados.put(NOTARIA, sinCanton);
    UUID id = remisionCompleta();

    assertEquals(NotaryEscrituraService.ORIGEN_NOTARIA, campos(id).get("canton_notaria").origen());
  }

  @Test
  void generarConDatosCompletosNoDejaTagsResiduales() throws Exception {
    UUID id = remisionCompleta();

    NotaryDtos.EscrituraDto escritura = generarCompleta(id);

    assertThat(escritura.camposVacios()).isEmpty();
    assertEquals(1, escritura.versionNo());
    assertEquals("escritura_LEX-2026-001_v1.docx", escritura.fileName());
    String texto = textoXml(partes(docx(escritura)));
    assertThat(texto).doesNotContain("{{").doesNotContain("}}");
    assertThat(texto)
        .contains("Dato nombre_vendedor")
        .contains(NotaryEscrituraService.ENTIDAD_BIESS)
        .contains("Quito")
        .contains("Valor fecha_otorgamiento");
    assertEquals(NotaryRemisionEstado.RECIBIDO.name(), remisionesGuardadas.get(id).getEstado());
  }

  @Test
  void camposVaciosSeRenderizanEnBlancoYSeListan() throws Exception {
    UUID id = remisionCompleta();
    List<String> formulario = List.copyOf(campos(id).keySet());

    NotaryDtos.EscrituraDto escritura = service.generar(id, new NotaryDtos.GenerarEscrituraBody(Map.of()));

    assertThat(escritura.camposVacios()).containsExactlyElementsOf(formulario);
    assertThat(textoXml(partes(docx(escritura)))).doesNotContain("{{");
  }

  @Test
  void valorEnBlancoTambienCuentaComoVacio() {
    UUID id = remisionCompleta();

    NotaryDtos.EscrituraDto escritura =
        service.generar(
            id, new NotaryDtos.GenerarEscrituraBody(Map.of("fecha_otorgamiento", "   ")));

    assertThat(escritura.camposVacios()).contains("fecha_otorgamiento");
  }

  @Test
  void versionMigradaResponde409() {
    for (String producto : new String[] {null, PRODUCTO}) {
      UUID id = remision("{}", producto);

      ApiException error = assertThrows(ApiException.class, () -> service.formulario(id));

      assertEstado(HttpStatus.CONFLICT, error);
      assertEquals(NotaryEscrituraService.VERSION_MIGRADA, error.getMessage());
    }
  }

  @Test
  void versionSinProductoResponde409ComoMigrada() {
    UUID id = remision(payloadCompleto(), null);

    ApiException error = assertThrows(ApiException.class, () -> service.formulario(id));

    assertEquals(NotaryEscrituraService.VERSION_MIGRADA, error.getMessage());
  }

  @Test
  void productoSinPlantillaResponde409() {
    UUID id = remision(payloadCompleto(), MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA);

    ApiException error = assertThrows(ApiException.class, () -> service.formulario(id));

    assertEstado(HttpStatus.CONFLICT, error);
    assertEquals(
        "No hay plantilla de escritura notarial para el producto SUSTITUCION_HIPOTECA",
        error.getMessage());
  }

  @Test
  void enviadoATerminalesResponden409() {
    UUID enviada = remisionCompleta();
    NotaryRemision sinRecibir =
        NotaryRemision.create(FIRMA, NOTARIA, UUID.randomUUID(), null, "X", "Y", null, 0, USUARIO, null);
    remisionesGuardadas.put(sinRecibir.getId(), sinRecibir);
    UUID devuelta = remisionCompleta();
    remisionesGuardadas.get(devuelta).devolverAlBufete(null);
    UUID completada = remisionCompleta();
    for (int i = 0; i < NotaryProcessSteps.COUNT - 1; i++) {
      remisionesGuardadas.get(completada).advanceStep();
    }

    for (UUID id : List.of(sinRecibir.getId(), devuelta, completada)) {
      assertEstado(
          HttpStatus.CONFLICT,
          assertThrows(
              ApiException.class,
              () -> service.generar(id, new NotaryDtos.GenerarEscrituraBody(Map.of()))));
    }
    assertThat(escriturasGuardadas).isEmpty();
    assertThat(enviada).isNotNull();
  }

  @Test
  void enTramiteSePuedeGenerar() {
    UUID id = remisionCompleta();
    remisionesGuardadas.get(id).advanceStep();

    assertThat(generarCompleta(id).id()).isNotNull();
    assertEquals(NotaryRemisionEstado.EN_TRAMITE.name(), remisionesGuardadas.get(id).getEstado());
  }

  @Test
  void bufeteYOtraNotariaRecibenNotFound() {
    UUID id = remisionCompleta();

    for (UUID ajeno : List.of(FIRMA, OTRA_NOTARIA)) {
      sesion(ajeno);
      assertEstado(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.formulario(id)));
      assertEstado(
          HttpStatus.NOT_FOUND,
          assertThrows(
              ApiException.class,
              () -> service.generar(id, new NotaryDtos.GenerarEscrituraBody(Map.of()))));
      assertEstado(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.historial(id)));
    }
    verify(escrituras, never()).save(any());
  }

  @Test
  void claveDesconocidaOCubiertaResponde400() {
    UUID id = remisionCompleta();

    for (String clave : List.of("inventada", "nombre_vendedor", "canton_notaria")) {
      ApiException error =
          assertThrows(
              ApiException.class,
              () -> service.generar(id, new NotaryDtos.GenerarEscrituraBody(Map.of(clave, "x"))));
      assertEstado(HttpStatus.BAD_REQUEST, error);
      assertThat(error.getMessage()).contains(clave);
    }
    assertThat(escriturasGuardadas).isEmpty();
  }

  @Test
  void sinLogoUsaElPixelTransparente() throws Exception {
    UUID id = remisionCompleta();
    byte[] pixel = logos.logoParaRender(NOTARIA);

    Map<String, byte[]> partes = partes(docx(generarCompleta(id)));

    List<byte[]> medios =
        partes.entrySet().stream()
            .filter(parte -> parte.getKey().startsWith("word/media/"))
            .map(Map.Entry::getValue)
            .toList();
    assertThat(medios).hasSize(1);
    assertThat(medios.get(0)).isEqualTo(pixel);
  }

  @Test
  void conLogoSeEscalaA120PxManteniendoLaProporcion() throws Exception {
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB), "png", png);
    logos.subir(new MockMultipartFile("file", "logo.png", "image/png", png.toByteArray()));
    UUID id = remisionCompleta();

    Map<String, byte[]> partes = partes(docx(generarCompleta(id)));

    String encabezados =
        partes.entrySet().stream()
            .filter(parte -> parte.getKey().matches("word/header\\d*\\.xml"))
            .map(parte -> new String(parte.getValue(), StandardCharsets.UTF_8))
            .reduce("", String::concat);
    // 120 × 60 px en EMU (9525 por px).
    assertThat(encabezados).contains("cx=\"1143000\"").contains("cy=\"571500\"");
  }

  @Test
  void rollbackBorraElArchivo() {
    UUID id = remisionCompleta();
    TransactionSynchronizationManager.initSynchronization();
    try {
      NotaryDtos.EscrituraDto escritura = generarCompleta(id);
      Path archivo = Path.of(escrituraGuardada(escritura).getStorageRef());
      assertThat(Files.exists(archivo)).isTrue();

      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_ROLLED_BACK);

      assertThat(Files.exists(archivo)).isFalse();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void historialConservaCadaGeneracionYDescargaSuArchivo() {
    UUID id = remisionCompleta();
    NotaryDtos.EscrituraDto primera = service.generar(id, new NotaryDtos.GenerarEscrituraBody(Map.of()));
    NotaryDtos.EscrituraDto segunda = generarCompleta(id);

    assertThat(service.historial(id))
        .extracting(NotaryDtos.EscrituraDto::id)
        .containsExactlyInAnyOrder(primera.id(), segunda.id());
    assertThat(service.archivo(id, segunda.id()).bytes()).isEqualTo(docx(segunda));
    assertThat(escrituraGuardada(primera).getStorageRef())
        .isNotEqualTo(escrituraGuardada(segunda).getStorageRef());

    sesion(OTRA_NOTARIA);
    assertEstado(
        HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.archivo(id, segunda.id())));
  }

  @Test
  void guardaLosDatosDelFormularioEnviados() throws Exception {
    UUID id = remisionCompleta();

    NotaryDtos.EscrituraDto escritura =
        service.generar(
            id, new NotaryDtos.GenerarEscrituraBody(Map.of("fecha_otorgamiento", " 9 de noviembre ")));

    assertEquals(
        "9 de noviembre",
        mapper.readTree(escrituraGuardada(escritura).getDatosFormulario())
            .path("fecha_otorgamiento")
            .asText());
    verify(authorization).requirePermission("notaria:remision:actuar");
  }
}
