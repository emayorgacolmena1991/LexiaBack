package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.common.storage.ArchivoStorageLocal;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.ExpedienteDocumentoStorage;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class NotaryLogoServiceTest {

  private static final UUID NOTARIA = UUID.randomUUID();
  private static final UUID OTRA_NOTARIA = UUID.randomUUID();
  private static final UUID BUFETE = UUID.randomUUID();
  private static final String PERMISO = "notaria:partnership:gestionar";
  private static final byte[] PNG = imagen("png");
  private static final byte[] JPEG = imagen("jpeg");

  @TempDir Path almacenamiento;

  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final Map<UUID, NotaryTenantProfile> perfiles = new HashMap<>();
  private ExpedienteDocumentoStorage storage;
  private NotaryLogoService service;

  @BeforeEach
  void setUp() {
    perfiles.put(NOTARIA, perfil(NOTARIA, "1790000000001"));
    perfiles.put(OTRA_NOTARIA, perfil(OTRA_NOTARIA, "1790000000002"));
    NotaryTenantProfileRepository repositorio = mock(NotaryTenantProfileRepository.class);
    when(repositorio.findById(any()))
        .thenAnswer(llamada -> Optional.ofNullable(perfiles.get(llamada.<UUID>getArgument(0))));
    storage = new ExpedienteDocumentoStorage(new ArchivoStorageLocal(almacenamiento));
    service =
        new NotaryLogoService(
            authorization, repositorio, storage, mock(AuditEventRepository.class));
    sesion(NOTARIA);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  private static byte[] imagen(String formato) {
    try {
      ByteArrayOutputStream salida = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), formato, salida);
      return salida.toByteArray();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private static NotaryTenantProfile perfil(UUID tenant, String ruc) {
    return NotaryTenantProfile.create(tenant, ruc, "Quito", "12", "Notaría", "Titular", null, true);
  }

  private static void sesion(UUID tenant) {
    AuthContext.set(
        new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), tenant, UUID.randomUUID()));
  }

  /** La extensión y el tipo declarado son falsos a propósito: solo cuentan los magic bytes. */
  private static MockMultipartFile archivo(String nombre, String tipo, byte[] bytes) {
    return new MockMultipartFile("file", nombre, tipo, bytes);
  }

  private static void assertEstado(HttpStatus esperado, ApiException error) {
    assertEquals(esperado, error.getStatus());
  }

  @Test
  void subirPngGuardaElLogoEnLaRutaDeLaNotaria() {
    service.subir(archivo("logo.txt", "text/plain", PNG));

    NotaryTenantProfile perfil = perfiles.get(NOTARIA);
    assertEquals("image/png", perfil.getLogoContentType());
    assertThat(perfil.getLogoUpdatedAt()).isNotNull();
    assertThat(perfil.getLogoStorageRef())
        .startsWith(NOTARIA + "/perfil-notaria/")
        .endsWith("_logo.png");
    assertThat(service.leer().bytes()).isEqualTo(PNG);
    assertEquals("image/png", service.leer().mimeType());
  }

  @Test
  void subirJpegPorMagicBytes() {
    service.subir(archivo("logo.png", "image/png", JPEG));

    assertEquals("image/jpeg", perfiles.get(NOTARIA).getLogoContentType());
    assertEquals("logo.jpg", service.leer().fileName());
  }

  @Test
  void tipoInvalidoResponde400AunqueDigaSerPng() {
    MockMultipartFile falso = archivo("logo.png", "image/png", "%PDF-1.7".getBytes());

    assertEstado(
        HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.subir(falso)));
    assertThat(perfiles.get(NOTARIA).getLogoStorageRef()).isNull();
  }

  @Test
  void pngTruncadoResponde400() {
    byte[] truncado = Arrays.copyOf(PNG, 4);

    assertEstado(
        HttpStatus.BAD_REQUEST,
        assertThrows(
            ApiException.class, () -> service.subir(archivo("l.png", "image/png", truncado))));
  }

  @Test
  void pngConFirmaValidaYContenidoCorruptoResponde400() {
    byte[] corrupto = Arrays.copyOf(PNG, 64);
    Arrays.fill(corrupto, 8, corrupto.length, (byte) 0x7F);

    assertEstado(
        HttpStatus.BAD_REQUEST,
        assertThrows(
            ApiException.class, () -> service.subir(archivo("l.png", "image/png", corrupto))));
    assertThat(perfiles.get(NOTARIA).getLogoStorageRef()).isNull();
  }

  @Test
  void jpegConFirmaValidaYContenidoCorruptoResponde400() {
    byte[] corrupto = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

    assertEstado(
        HttpStatus.BAD_REQUEST,
        assertThrows(
            ApiException.class, () -> service.subir(archivo("l.jpg", "image/jpeg", corrupto))));
  }

  /** PNG en blanco y negro: comprime a pocos bytes y cabe de sobra en 1 MB. */
  private static byte[] pngDe(int ancho, int alto) {
    try {
      ByteArrayOutputStream salida = new ByteArrayOutputStream();
      ImageIO.write(
          new BufferedImage(ancho, alto, BufferedImage.TYPE_BYTE_BINARY), "png", salida);
      return salida.toByteArray();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /** Firma e IHDR sin píxeles: decodificarlo fallaría con otro mensaje. */
  private static byte[] pngSoloCabecera(int ancho, int alto) {
    byte[] datos = Arrays.copyOf(PNG, 33);
    ByteBuffer.wrap(datos).putInt(16, ancho).putInt(20, alto);
    return datos;
  }

  private static byte[] jpegCmyk() throws IOException {
    WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 8, 8, 4, null);
    ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
    ByteArrayOutputStream salida = new ByteArrayOutputStream();
    try (ImageOutputStream destino = ImageIO.createImageOutputStream(salida)) {
      escritor.setOutput(destino);
      escritor.write(null, new IIOImage(raster, null, null), null);
    } finally {
      escritor.dispose();
    }
    return salida.toByteArray();
  }

  private void assertRechazado(byte[] bytes, String tipo, String mensaje) {
    ApiException error =
        assertThrows(ApiException.class, () -> service.subir(archivo("logo", tipo, bytes)));

    assertEstado(HttpStatus.BAD_REQUEST, error);
    assertEquals(mensaje, error.getMessage());
    assertThat(perfiles.get(NOTARIA).getLogoStorageRef()).isNull();
  }

  @Test
  void anchoMayorA4000Responde400() {
    assertRechazado(pngDe(4001, 1), "image/png", "El logo no puede superar 4000×4000 px");
  }

  @Test
  void altoMayorA4000Responde400() {
    assertRechazado(pngDe(1, 4001), "image/png", "El logo no puede superar 4000×4000 px");
  }

  @Test
  void dimensionesEnormesSeRechazanSinDecodificarLosPixeles() {
    assertRechazado(
        pngSoloCabecera(100_000, 100_000), "image/png", "El logo no puede superar 4000×4000 px");
  }

  @Test
  void exactamente4000Por4000SeAcepta() {
    service.subir(archivo("l.png", "image/png", pngDe(4000, 4000)));

    assertEquals("image/png", perfiles.get(NOTARIA).getLogoContentType());
  }

  @Test
  void jpegCmykResponde400ConMensajeClaro() throws Exception {
    assertRechazado(jpegCmyk(), "image/jpeg", "Use PNG o JPEG en RGB");
  }

  @Test
  void archivoVacioResponde400() {
    assertEstado(
        HttpStatus.BAD_REQUEST,
        assertThrows(
            ApiException.class, () -> service.subir(archivo("l.png", "image/png", new byte[0]))));
  }

  @Test
  void masDeUnMegaResponde400() {
    byte[] grande = Arrays.copyOf(PNG, (int) NotaryLogoService.MAX_BYTES + 1);

    assertEstado(
        HttpStatus.BAD_REQUEST,
        assertThrows(
            ApiException.class, () -> service.subir(archivo("l.png", "image/png", grande))));
  }

  @Test
  void exactamenteUnMegaSeAcepta() {
    byte[] limite = Arrays.copyOf(PNG, (int) NotaryLogoService.MAX_BYTES);

    service.subir(archivo("l.png", "image/png", limite));

    assertEquals("image/png", perfiles.get(NOTARIA).getLogoContentType());
  }

  @Test
  void otraNotariaNoVeNiQuitaElLogoAjeno() {
    service.subir(archivo("l.png", "image/png", PNG));
    sesion(OTRA_NOTARIA);

    assertEstado(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, service::leer));
    assertEstado(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, service::quitar));
    assertThat(perfiles.get(NOTARIA).getLogoStorageRef()).isNotNull();
  }

  @Test
  void tenantSinPerfilDeNotariaResponde404() {
    sesion(BUFETE);

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(
            ApiException.class, () -> service.subir(archivo("l.png", "image/png", PNG))));
  }

  @Test
  void sinPermisoResponde403() {
    doThrow(new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sin permiso"))
        .when(authorization)
        .requirePermission(PERMISO);

    AuthException error =
        assertThrows(
            AuthException.class, () -> service.subir(archivo("l.png", "image/png", PNG)));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
  }

  @Test
  void leerSinLogoResponde404() {
    assertEstado(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, service::leer));
  }

  @Test
  void quitarVaciaElPerfilYBorraElArchivoAlConfirmar() {
    service.subir(archivo("l.png", "image/png", PNG));
    Path archivo = almacenamiento.resolve(perfiles.get(NOTARIA).getLogoStorageRef());

    TransactionSynchronizationManager.initSynchronization();
    try {
      service.quitar();
      assertThat(Files.exists(archivo)).isTrue();

      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_COMMITTED);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    NotaryTenantProfile perfil = perfiles.get(NOTARIA);
    assertThat(perfil.getLogoStorageRef()).isNull();
    assertThat(perfil.getLogoContentType()).isNull();
    assertThat(Files.exists(archivo)).isFalse();
  }

  @Test
  void reemplazarBorraElLogoAnteriorAlConfirmar() {
    service.subir(archivo("l.png", "image/png", PNG));
    Path anterior = almacenamiento.resolve(perfiles.get(NOTARIA).getLogoStorageRef());

    TransactionSynchronizationManager.initSynchronization();
    try {
      service.subir(archivo("l.jpg", "image/jpeg", JPEG));
      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_COMMITTED);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertThat(Files.exists(anterior)).isFalse();
    assertThat(service.leer().bytes()).isEqualTo(JPEG);
  }

  @Test
  void rollbackBorraElLogoNuevoYConservaElAnterior() {
    service.subir(archivo("l.png", "image/png", PNG));
    Path anterior = almacenamiento.resolve(perfiles.get(NOTARIA).getLogoStorageRef());

    TransactionSynchronizationManager.initSynchronization();
    try {
      service.subir(archivo("l.jpg", "image/jpeg", JPEG));
      Path nuevo = almacenamiento.resolve(perfiles.get(NOTARIA).getLogoStorageRef());

      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_ROLLED_BACK);

      assertThat(Files.exists(nuevo)).isFalse();
      assertThat(Files.exists(anterior)).isTrue();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void logoParaRenderSinLogoDevuelvePngTransparenteDeUnPixel() throws Exception {
    byte[] bytes = service.logoParaRender(NOTARIA);

    BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(bytes));
    assertThat(imagen).isNotNull();
    assertEquals(1, imagen.getWidth());
    assertEquals(1, imagen.getHeight());
    assertEquals(0, imagen.getRGB(0, 0) >>> 24);
  }

  @Test
  void logoParaRenderDeTenantSinPerfilTambienDevuelveElPixel() throws Exception {
    assertThat(ImageIO.read(new ByteArrayInputStream(service.logoParaRender(BUFETE)))).isNotNull();
  }

  @Test
  void logoParaRenderDevuelveElLogoDesdeLaSesionDelBufete() {
    service.subir(archivo("l.png", "image/png", PNG));
    sesion(BUFETE);

    assertThat(service.logoParaRender(NOTARIA)).isEqualTo(PNG);
  }
}
