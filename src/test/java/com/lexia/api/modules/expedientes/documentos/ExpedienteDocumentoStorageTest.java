package com.lexia.api.modules.expedientes.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.common.storage.ArchivoStorageLocal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpedienteDocumentoStorageTest {

  private static final UUID TENANT = UUID.fromString("a1000000-0000-7000-8000-000000000001");
  private static final UUID CASE = UUID.fromString("a1000000-0000-7000-8000-000000000002");
  private static final UUID DOC = UUID.fromString("a1000000-0000-7000-8000-000000000003");

  @TempDir Path base;

  private Path raiz;
  private Path expedientesHeredado;
  private ExpedienteDocumentoStorage storage;

  @BeforeEach
  void setUp() throws Exception {
    raiz = Files.createDirectories(base.resolve("data"));
    expedientesHeredado = Files.createDirectories(base.resolve("legacy").resolve("expedientes"));
    storage = new ExpedienteDocumentoStorage(new ArchivoStorageLocal(raiz, expedientesHeredado));
  }

  @Test
  void claveNuevaEsRelativaConTenantCasoDocumentoYVersion() {
    ExpedienteDocumentoStorage.Stored stored =
        storage.guardarVersion(TENANT, CASE, DOC, 1, "Cédula Nueva.PDF", "application/pdf", new byte[] {9, 8, 7});

    assertThat(stored.clave())
        .startsWith(TENANT + "/expedientes/" + CASE + "/" + DOC + "/v1_")
        .endsWith("_c_dula_nueva.pdf");
    assertThat(Path.of(stored.clave()).isAbsolute()).isFalse();
    assertArrayEquals(new byte[] {9, 8, 7}, storage.read(stored.clave()));
    assertEquals(3, storage.size(stored.clave()));
  }

  @Test
  void mismaVersionYNombreDosVecesDaClavesDistintas() {
    String a = storage.guardarVersion(TENANT, CASE, DOC, 1, "x.pdf", "application/pdf", new byte[] {1}).clave();
    String b = storage.guardarVersion(TENANT, CASE, DOC, 1, "x.pdf", "application/pdf", new byte[] {2}).clave();

    assertThat(a).isNotEqualTo(b);
    assertArrayEquals(new byte[] {1}, storage.read(a));
  }

  @Test
  void leeLaRutaAbsolutaHeredadaDentroDeLaCarpetaDeExpedientes() throws Exception {
    Path vieja =
        expedientesHeredado.resolve(TENANT.toString()).resolve(CASE.toString()).resolve(DOC + "_cedula.pdf");
    Files.createDirectories(vieja.getParent());
    Files.write(vieja, new byte[] {1, 2, 3});

    assertArrayEquals(new byte[] {1, 2, 3}, storage.read(vieja.toString()));
    assertEquals(3, storage.size(vieja.toString()));
  }

  @Test
  void leeArchivosGuardadosConLaRutaAbsolutaDeOtroEquipo() throws Exception {
    Path real =
        expedientesHeredado.resolve(TENANT.toString()).resolve(CASE.toString()).resolve(DOC + "_cedula.pdf");
    Files.createDirectories(real.getParent());
    Files.write(real, new byte[] {1, 2, 3});
    String otroEquipo =
        "C:\\Users\\otro\\Lexia\\LexiaBack\\data\\expedientes\\" + TENANT + "\\" + CASE + "\\" + DOC + "_cedula.pdf";

    assertArrayEquals(new byte[] {1, 2, 3}, storage.read(otroEquipo));
    assertEquals(3, storage.size(otroEquipo));
  }

  @Test
  void rutaDeOtroEquipoSinArchivoAquiEs404() {
    String otroEquipo =
        base.resolve("otro-equipo").resolve(TENANT.toString()).resolve(DOC + "_cedula.pdf").toString();

    ApiException ex = assertThrows(ApiException.class, () -> storage.read(otroEquipo));

    assertEquals("ARCHIVO_NO_DISPONIBLE", ex.getCode());
    assertEquals(0, storage.size(otroEquipo), "un listado no se rompe por una fila sin archivo");
  }

  @Test
  void readFallaSiElArchivoNoEstaEnEsteDisco() {
    ApiException ex =
        assertThrows(ApiException.class, () -> storage.read(TENANT + "/expedientes/" + CASE + "/fantasma.pdf"));
    assertEquals("Archivo no disponible en el almacenamiento.", ex.getMessage());
  }
}
