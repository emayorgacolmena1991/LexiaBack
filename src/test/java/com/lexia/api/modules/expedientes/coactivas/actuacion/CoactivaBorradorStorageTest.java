package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.common.storage.ArchivoStorageLocal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** .docx de los borradores de actuación sobre ArchivoStorage. */
class CoactivaBorradorStorageTest {

  private static final UUID TENANT = UUID.randomUUID();

  @TempDir Path base;

  private Path raiz;
  private Path heredada;
  private CoactivaGenerationService service;

  @BeforeEach
  void setUp() throws Exception {
    raiz = Files.createDirectories(base.resolve("data"));
    heredada = Files.createDirectories(base.resolve("legacy").resolve("coactivas"));
    service =
        new CoactivaGenerationService(
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            new ArchivoStorageLocal(raiz, heredada),
            "America/Guayaquil");
  }

  private static CoactivaDocumentoDraft draft() {
    return CoactivaDocumentoDraft.crear(TENANT, UUID.randomUUID(), UUID.randomUUID(), "EMBARGO", UUID.randomUUID());
  }

  @Test
  void cadaRenderGuardaUnaClaveRelativaNuevaConTenant() {
    CoactivaDocumentoDraft draft = draft();

    String primera = service.persistDocx(draft, "Providencia de embargo", new byte[] {1});
    String segunda = service.persistDocx(draft, "Providencia de embargo", new byte[] {2});

    assertThat(primera).isNotEqualTo(segunda);
    assertThat(primera)
        .startsWith(TENANT + "/coactivas/borradores/" + draft.getId() + "_")
        .endsWith(".docx");
    assertTrue(Files.isRegularFile(raiz.resolve(primera)), "nunca sobrescribe");
    draft.guardar(null, null, null, segunda, 0);
    assertArrayEquals(new byte[] {2}, service.leerDocx(draft));
  }

  @Test
  void borradorHeredadoConRutaAbsolutaSeSigueLeyendo() throws Exception {
    CoactivaDocumentoDraft draft = draft();
    Path viejo = Files.write(heredada.resolve(draft.getId() + "_providencia.docx"), new byte[] {7});
    draft.guardar(null, null, null, viejo.toString(), 0);

    assertArrayEquals(new byte[] {7}, service.leerDocx(draft));

    draft.guardar(null, null, null, "D:\\otro\\data\\coactivas\\" + draft.getId() + "_providencia.docx", 0);
    assertArrayEquals(new byte[] {7}, service.leerDocx(draft), "reubicado bajo la carpeta actual");
  }
}
