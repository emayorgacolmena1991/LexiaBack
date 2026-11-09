package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lexia.api.common.api.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpedienteDocumentoStorageTest {

  private static final UUID TENANT = UUID.fromString("a1000000-0000-7000-8000-000000000001");
  private static final UUID CASE = UUID.fromString("a1000000-0000-7000-8000-000000000002");
  private static final UUID DOC = UUID.fromString("a1000000-0000-7000-8000-000000000003");

  @TempDir Path root;

  @Test
  void leeArchivosGuardadosConLaRutaAbsolutaDeOtroEquipo() throws Exception {
    Path real = root.resolve(TENANT.toString()).resolve(CASE.toString()).resolve(DOC + "_cedula.pdf");
    Files.createDirectories(real.getParent());
    Files.write(real, new byte[] {1, 2, 3});
    ExpedienteDocumentoStorage storage = new ExpedienteDocumentoStorage(root.toString());

    byte[] bytes =
        storage.read(
            "C:\\Users\\otro\\Lexia\\LexiaBack\\data\\expedientes\\"
                + TENANT
                + "\\"
                + CASE
                + "\\"
                + DOC
                + "_cedula.pdf");
    assertArrayEquals(new byte[] {1, 2, 3}, bytes);
    assertEquals(3, storage.size(real.toString()));
  }

  @Test
  void writeCreaBytesAunqueNoHubieraArchivoPrevioEnEsaRuta() {
    ExpedienteDocumentoStorage storage = new ExpedienteDocumentoStorage(root.toString());
    ExpedienteDocumentoStorage.Stored stored =
        storage.write(TENANT, CASE, DOC, "nuevo.pdf", new byte[] {9, 8, 7});
    assertArrayEquals(new byte[] {9, 8, 7}, storage.read(stored.path()));
  }

  @Test
  void readFallaSiElArchivoNoEstaEnEsteDisco() {
    ExpedienteDocumentoStorage storage = new ExpedienteDocumentoStorage(root.toString());
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                storage.read(
                    root.resolve(TENANT.toString())
                        .resolve(CASE.toString())
                        .resolve("fantasma.pdf")
                        .toString()));
    assertEquals("Archivo no disponible en el almacenamiento.", ex.getMessage());
  }
}
