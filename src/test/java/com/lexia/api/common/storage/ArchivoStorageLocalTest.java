package com.lexia.api.common.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.common.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class ArchivoStorageLocalTest {

  private static final UUID TENANT = UUID.fromString("a1000000-0000-7000-8000-000000000001");
  private static final UUID CASO = UUID.fromString("a1000000-0000-7000-8000-000000000002");
  private static final UUID DOC = UUID.fromString("a1000000-0000-7000-8000-000000000003");

  @TempDir Path base;

  private Path raiz;
  private Path heredada;
  private ArchivoStorageLocal storage;

  @BeforeEach
  void setUp() throws Exception {
    raiz = Files.createDirectories(base.resolve("data"));
    heredada = Files.createDirectories(base.resolve("viejo").resolve("expedientes"));
    storage = new ArchivoStorageLocal(raiz, heredada);
  }

  private String guardar(String clave, byte... bytes) {
    return storage.guardar(clave, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
  }

  @Test
  void guardaYLeePorClaveRelativa() throws Exception {
    assertEquals("t/expedientes/c/d/v1_x.pdf", guardar("t/expedientes/c/d/v1_x.pdf", (byte) 1, (byte) 2));

    assertArrayEquals(new byte[] {1, 2}, storage.leer("t/expedientes/c/d/v1_x.pdf"));
    assertTrue(storage.existe("t/expedientes/c/d/v1_x.pdf"));
    assertEquals(2, storage.tamano("t/expedientes/c/d/v1_x.pdf"));
    try (InputStream in = storage.abrir("t/expedientes/c/d/v1_x.pdf")) {
      assertArrayEquals(new byte[] {1, 2}, in.readAllBytes());
    }
    assertTrue(Files.isRegularFile(raiz.resolve("t/expedientes/c/d/v1_x.pdf")));
  }

  @Test
  void mismaClaveDosVecesLanzaYNoSobrescribe() {
    guardar("t/a.pdf", (byte) 1);

    ApiException ex = assertThrows(ApiException.class, () -> guardar("t/a.pdf", (byte) 9));

    assertEquals("STORAGE_KEY_EXISTS", ex.getCode());
    assertArrayEquals(new byte[] {1}, storage.leer("t/a.pdf"));
  }

  @Test
  void tamanoDistintoAlEsperadoNoDejaArchivo() {
    assertThrows(
        ApiException.class,
        () -> storage.guardar("t/b.pdf", new ByteArrayInputStream(new byte[] {1}), 5, "application/pdf"));

    assertFalse(storage.existe("t/b.pdf"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../fuera.pdf", "t/../../fuera.pdf", "t/..", "C:x.pdf", "", " "})
  void claveNuevaConFormatoInvalidoEs500(String clave) {
    ApiException alGuardar = assertThrows(ApiException.class, () -> guardar(clave, (byte) 1));
    ApiException alLeer = assertThrows(ApiException.class, () -> storage.leer(clave));

    for (ApiException ex : new ApiException[] {alGuardar, alLeer}) {
      assertEquals("STORAGE_KEY_INVALID", ex.getCode());
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/t/a.pdf", "\\t\\a.pdf", "C:\\t\\a.pdf"})
  void guardarConRutaAbsolutaSeRechaza(String clave) {
    ApiException ex = assertThrows(ApiException.class, () -> guardar(clave, (byte) 1));
    assertEquals("STORAGE_KEY_INVALID", ex.getCode());
  }

  @Test
  void guardarConRutaAbsolutaDentroDeLaRaizTambienSeRechaza() {
    String absoluta = raiz.resolve("t/abs.pdf").toString();
    ApiException ex = assertThrows(ApiException.class, () -> guardar(absoluta, (byte) 1));
    assertEquals("STORAGE_KEY_INVALID", ex.getCode());
  }

  @Test
  void rutaHeredadaDentroDeCarpetaPermitidaSeLee() throws Exception {
    Path vieja = heredada.resolve("tenant").resolve("caso").resolve("doc_cedula.pdf");
    Files.createDirectories(vieja.getParent());
    Files.write(vieja, new byte[] {4, 5, 6});

    assertArrayEquals(new byte[] {4, 5, 6}, storage.leer(vieja.toString()));
    assertTrue(storage.existe(vieja.toString()));
    assertEquals(3, storage.tamano(vieja.toString()));
  }

  @Test
  void rutaHeredadaFueraDeLasCarpetasPermitidasEs404AunqueExista() throws Exception {
    Path fuera = Files.createDirectories(base.resolve("otro")).resolve("secreto.pdf");
    Files.write(fuera, new byte[] {7});
    Path escapada = heredada.resolve("..").resolve("..").resolve("otro").resolve("secreto.pdf");

    for (String clave : new String[] {fuera.toString(), escapada.toString(), heredada.toString()}) {
      assertNoDisponibleEnEsteServidor(clave);
    }
  }

  @Test
  void rutaAbsolutaDeOtraRaizSeReubicaBajoLaCarpetaActual() throws Exception {
    String cola = TENANT + "/" + CASO + "/" + DOC + "_cedula.pdf";
    Path real = heredada.resolve(cola);
    Files.createDirectories(real.getParent());
    Files.write(real, new byte[] {1, 2, 3});
    String windows = "C:\\Users\\otro\\Lexia\\data\\expedientes\\" + cola.replace('/', '\\');
    String linux = "/srv/lexia/data/expedientes/" + cola;

    for (String clave : new String[] {windows, linux}) {
      assertArrayEquals(new byte[] {1, 2, 3}, storage.leer(clave));
      assertTrue(storage.existe(clave));
      assertEquals(3, storage.tamano(clave));
    }
  }

  @Test
  void conPuntosEnLaColaNoSeReubica() throws Exception {
    UUID otroTenant = UUID.randomUUID();
    Path ajeno = heredada.resolve(otroTenant + "/" + CASO + "/x.pdf");
    Files.createDirectories(ajeno.getParent());
    Files.write(ajeno, new byte[] {9});

    assertNoDisponibleEnEsteServidor(
        "C:\\otra\\expedientes\\" + TENANT + "\\..\\" + otroTenant + "\\" + CASO + "\\x.pdf");
    assertNoDisponibleEnEsteServidor("/otra/expedientes/" + TENANT + "/../" + otroTenant + "/" + CASO + "/x.pdf");
  }

  @Test
  void rutaHeredadaSinArchivoEnEsteServidorEs404() {
    assertNoDisponibleEnEsteServidor("C:\\otra\\expedientes\\" + TENANT + "\\" + CASO + "\\nada.pdf");
    storage.borrar("C:\\otra\\expedientes\\" + TENANT + "\\" + CASO + "\\nada.pdf");
  }

  private void assertNoDisponibleEnEsteServidor(String clave) {
    ApiException ex = assertThrows(ApiException.class, () -> storage.leer(clave), clave);
    assertEquals("ARCHIVO_NO_DISPONIBLE", ex.getCode());
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    assertEquals("El archivo no está disponible en este servidor.", ex.getMessage());
    assertFalse(storage.existe(clave));
  }

  @Test
  void leerUnaClaveInexistenteEs404() {
    ApiException ex = assertThrows(ApiException.class, () -> storage.leer("t/fantasma.pdf"));
    assertEquals("Archivo no disponible en el almacenamiento.", ex.getMessage());
    assertFalse(storage.existe("t/fantasma.pdf"));
  }

  @Test
  void sustituirAlTerminarBorraLaAnteriorEnCommitYLaNuevaEnRollback() {
    guardar("t/v1.pdf", (byte) 1);
    guardar("t/v2.pdf", (byte) 2);
    guardar("t/v3.pdf", (byte) 3);

    conTransaccion(TransactionSynchronization.STATUS_COMMITTED, () -> storage.sustituirAlTerminar("t/v2.pdf", "t/v1.pdf"));
    conTransaccion(TransactionSynchronization.STATUS_ROLLED_BACK, () -> storage.sustituirAlTerminar("t/v3.pdf", "t/v2.pdf"));

    assertFalse(storage.existe("t/v1.pdf"));
    assertTrue(storage.existe("t/v2.pdf"));
    assertFalse(storage.existe("t/v3.pdf"));
  }

  @Test
  void siBorrarLaAnteriorFallaSoloQuedaHuerfanaYNoLanza() {
    ArchivoStorageLocal fallaAlBorrar =
        new ArchivoStorageLocal(raiz, heredada) {
          @Override
          public void borrar(String clave) {
            throw new IllegalStateException("disco");
          }
        };
    fallaAlBorrar.guardar("t/viejo.png", new ByteArrayInputStream(new byte[] {1}), 1, "image/png");

    conTransaccion(TransactionSynchronization.STATUS_COMMITTED, () -> fallaAlBorrar.sustituirAlTerminar("t/nuevo.png", "t/viejo.png"));

    assertTrue(storage.existe("t/viejo.png"), "huérfano, sin excepción");
  }

  private static void conTransaccion(int status, Runnable registrar) {
    TransactionSynchronizationManager.initSynchronization();
    try {
      registrar.run();
      TransactionSynchronizationUtils.triggerAfterCompletion(status);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void borrarEliminaYUnaClaveYaBorradaNoFalla() {
    guardar("t/c.pdf", (byte) 1);

    storage.borrar("t/c.pdf");
    storage.borrar("t/c.pdf");

    assertFalse(storage.existe("t/c.pdf"));
  }
}
