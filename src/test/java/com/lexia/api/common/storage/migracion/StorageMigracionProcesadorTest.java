package com.lexia.api.common.storage.migracion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageMigracionProcesadorTest {

  private static final UUID TENANT = UUID.fromString("a1000000-0000-7000-8000-000000000001");

  @TempDir Path base;

  private Path root;
  private Path heredada;
  private StorageMigracionPaths paths;
  private MapStore bucket;

  @BeforeEach
  void setUp() throws Exception {
    root = Files.createDirectories(base.resolve("data"));
    heredada = Files.createDirectories(base.resolve("viejo").resolve("expedientes"));
    paths = new StorageMigracionPaths(root, heredada);
    bucket = new MapStore();
  }

  @Test
  void claveRelativaNuevaSeSube() throws Exception {
    String clave = TENANT + "/expedientes/caso/doc.pdf";
    byte[] data = new byte[] {1, 2, 3};
    Files.write(root.resolve(clave), data);

    var salida =
        StorageMigracionProcesador.procesar(
            fila("1", clave, null, "application/pdf"), paths, bucket, false);

    assertEquals(StorageMigracionResultado.SUBIDO, salida.resultado());
    assertTrue(bucket.existe(clave));
    assertEquals(3, bucket.tamano(clave));
  }

  @Test
  void claveRelativaYaEnBucketNoSobrescribe() throws Exception {
    String clave = TENANT + "/a.pdf";
    Files.write(root.resolve(clave), new byte[] {9});
    bucket.subir(clave, root.resolve(clave), "application/pdf");

    var salida =
        StorageMigracionProcesador.procesar(fila("1", clave, null, null), paths, bucket, false);

    assertEquals(StorageMigracionResultado.YA_ESTABA, salida.resultado());
    assertEquals(9, bucket.tamano(clave));
  }

  @Test
  void rutaAbsolutaWindowsSeNormaliza() throws Exception {
    Path vieja = heredada.resolve(TENANT.toString()).resolve("caso").resolve("doc.pdf");
    Files.createDirectories(vieja.getParent());
    Files.write(vieja, new byte[] {5, 6});
    String absoluta = vieja.toString();

    var salida =
        StorageMigracionProcesador.procesar(fila("1", absoluta, null, null), paths, bucket, false);

    assertEquals(StorageMigracionResultado.ACTUALIZADA, salida.resultado());
    String esperada = TENANT + "/caso/doc.pdf";
    assertEquals(esperada, salida.claveDespues());
    assertTrue(bucket.existe(esperada));
  }

  @Test
  void rutaAbsolutaUnixHeredada() throws Exception {
    Path vieja = heredada.resolve("tenant/caso/x.pdf");
    Files.createDirectories(vieja.getParent());
    Files.write(vieja, new byte[] {1});
    String absoluta = vieja.toAbsolutePath().toString().replace('\\', '/');

    var salida =
        StorageMigracionProcesador.procesar(fila("1", absoluta, null, null), paths, bucket, false);

    assertEquals(StorageMigracionResultado.ACTUALIZADA, salida.resultado());
    assertEquals("tenant/caso/x.pdf", salida.claveDespues());
  }

  @Test
  void archivoFaltante() {
    var salida =
        StorageMigracionProcesador.procesar(
            fila("1", TENANT + "/nope.pdf", null, null), paths, bucket, false);
    assertEquals(StorageMigracionResultado.FALTANTE, salida.resultado());
  }

  @Test
  void tamanoRemotoDistintoEsError() throws Exception {
    String clave = TENANT + "/b.pdf";
    Files.write(root.resolve(clave), new byte[] {1, 2});
    bucket.map.put(clave, 99L);

    var salida =
        StorageMigracionProcesador.procesar(fila("1", clave, null, null), paths, bucket, false);

    assertEquals(StorageMigracionResultado.ERROR, salida.resultado());
  }

  @Test
  void sha256NoCoincide() throws Exception {
    String clave = TENANT + "/c.pdf";
    byte[] data = new byte[] {7, 8};
    Files.write(root.resolve(clave), data);
    String mal = "00".repeat(32);

    var salida =
        StorageMigracionProcesador.procesar(fila("1", clave, mal, null), paths, bucket, false);

    assertEquals(StorageMigracionResultado.ERROR, salida.resultado());
  }

  @Test
  void dryRunNoSube() throws Exception {
    String clave = TENANT + "/d.pdf";
    Files.write(root.resolve(clave), new byte[] {1});

    var salida =
        StorageMigracionProcesador.procesar(fila("1", clave, null, null), paths, bucket, true);

    assertEquals(StorageMigracionResultado.DRY_RUN, salida.resultado());
    assertFalse(bucket.existe(clave));
  }

  @Test
  void segundaEjecucionSinCambios() throws Exception {
    String clave = TENANT + "/e.pdf";
    Files.write(root.resolve(clave), new byte[] {3, 3, 3});

    var primera =
        StorageMigracionProcesador.procesar(fila("1", clave, null, null), paths, bucket, false);
    assertEquals(StorageMigracionResultado.SUBIDO, primera.resultado());

    var segunda =
        StorageMigracionProcesador.procesar(fila("1", clave, null, null), paths, bucket, false);
    assertEquals(StorageMigracionResultado.YA_ESTABA, segunda.resultado());
    assertEquals(1, bucket.map.size());
  }

  private static StorageMigracionProcesador.Fila fila(
      String id, String ref, String hash, String mime) {
    return new StorageMigracionProcesador.Fila(id, ref, hash, mime);
  }

  /** S3 simulado en memoria (sin AWS). */
  static final class MapStore implements StorageMigracionObjectStore {
    final Map<String, Long> map = new LinkedHashMap<>();

    @Override
    public boolean existe(String clave) {
      return map.containsKey(clave);
    }

    @Override
    public long tamano(String clave) {
      return map.get(clave);
    }

    @Override
    public void subir(String clave, Path archivo, String contentType) {
      if (existe(clave)) {
        throw new IllegalStateException("STORAGE_KEY_EXISTS");
      }
      try {
        long size = Files.size(archivo);
        map.put(clave, size);
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  @SuppressWarnings("unused")
  private static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
}