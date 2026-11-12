package com.lexia.api.modules.expedientes.coactivas.archivos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.common.storage.ArchivoStorageLocal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class CoactivaArchivoStorageTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");

  @TempDir Path base;

  private Path raiz;
  private Path coactivasHeredada;
  private CoactivaArchivoStorage storage;

  @BeforeEach
  void setUp() throws Exception {
    raiz = Files.createDirectories(base.resolve("data"));
    coactivasHeredada = Files.createDirectories(base.resolve("legacy").resolve("coactivas"));
    storage = new CoactivaArchivoStorage(new ArchivoStorageLocal(raiz, coactivasHeredada));
  }

  @Test
  void subidaEnStreamingGuardaClaveRelativaConTenantYMes() {
    UUID archivoId = UUID.randomUUID();
    MockMultipartFile file = new MockMultipartFile("file", "Juicio Coactivo.pdf", "application/pdf", new byte[] {1, 2});

    CoactivaArchivoStorage.StoredFile stored = storage.store(TENANT, archivoId, file);

    LocalDate hoy = LocalDate.now();
    assertThat(stored.path())
        .startsWith(TENANT + "/coactivas/" + hoy.getYear() + "/" + String.format("%02d", hoy.getMonthValue()) + "/" + archivoId + "_")
        .endsWith("_Juicio_Coactivo.pdf");
    assertThat(stored.sha256()).hasSize(64);
    assertArrayEquals(new byte[] {1, 2}, storage.read(archivo(stored.path())));
  }

  @Test
  void mismoArchivoIdDosVecesNoSobrescribe() {
    UUID archivoId = UUID.randomUUID();
    String primera = storage.storeBytes(TENANT, archivoId, "acta.pdf", new byte[] {1}).path();
    String segunda = storage.storeBytes(TENANT, archivoId, "acta.pdf", new byte[] {2}).path();

    assertThat(primera).isNotEqualTo(segunda);
    assertArrayEquals(new byte[] {1}, storage.read(archivo(primera)));
    assertArrayEquals(new byte[] {2}, storage.read(archivo(segunda)));
  }

  @Test
  void leeArchivosGuardadosConLaRutaAbsolutaDeOtroEquipo() throws Exception {
    Path real = coactivasHeredada.resolve(TENANT.toString()).resolve("2026").resolve("10").resolve("a_juicio.pdf");
    Files.createDirectories(real.getParent());
    Files.write(real, new byte[] {1, 2, 3});

    CoactivaArchivo enEsteEquipo = archivo(real.toString());
    CoactivaArchivo otroEquipo =
        archivo("C:\\Users\\otro\\Lexia\\LexiaBack\\data\\coactivas\\" + TENANT + "\\2026\\10\\a_juicio.pdf");
    assertTrue(storage.disponible(enEsteEquipo));
    assertTrue(storage.disponible(otroEquipo));
    assertArrayEquals(new byte[] {1, 2, 3}, storage.read(otroEquipo));

    assertFalse(storage.disponible(archivo("/srv/lexia/" + TENANT + "/2026/10/no_existe.pdf")));
    assertFalse(storage.disponible(archivo("/srv/lexia/" + TENANT + "/../../etc/passwd")));
    assertFalse(storage.disponible(archivo("../fuera.pdf")), "clave nueva inválida: no disponible");
  }

  private static CoactivaArchivo archivo(String ruta) {
    return CoactivaArchivo.create(UUID.randomUUID(), TENANT, "EXPEDIENTE_UNIFICADO", "juicio.pdf", "application/pdf", 3, "sha", ruta, null);
  }
}
