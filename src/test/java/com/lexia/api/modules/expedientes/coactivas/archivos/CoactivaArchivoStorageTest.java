package com.lexia.api.modules.expedientes.coactivas.archivos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoactivaArchivoStorageTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");

  @TempDir Path root;

  @Test
  void leeArchivosGuardadosConLaRutaAbsolutaDeOtroEquipo() throws Exception {
    Path real = root.resolve(TENANT.toString()).resolve("2026").resolve("10").resolve("a_juicio.pdf");
    Files.createDirectories(real.getParent());
    Files.write(real, new byte[] {1, 2, 3});
    CoactivaArchivoStorage storage = new CoactivaArchivoStorage(root.toString());

    CoactivaArchivo otroEquipo =
        archivo("C:\\Users\\otro\\Lexia\\LexiaBack\\data\\coactivas\\" + TENANT + "\\2026\\10\\a_juicio.pdf");
    assertTrue(storage.disponible(otroEquipo));
    assertArrayEquals(new byte[] {1, 2, 3}, storage.read(otroEquipo));

    assertFalse(storage.disponible(archivo("/srv/lexia/" + TENANT + "/2026/10/no_existe.pdf")));
    assertFalse(storage.disponible(archivo("/srv/lexia/" + TENANT + "/../../etc/passwd")));
  }

  private static CoactivaArchivo archivo(String ruta) {
    return CoactivaArchivo.create(UUID.randomUUID(), TENANT, "EXPEDIENTE_UNIFICADO", "juicio.pdf", "application/pdf", 3, "sha", ruta, null);
  }
}
