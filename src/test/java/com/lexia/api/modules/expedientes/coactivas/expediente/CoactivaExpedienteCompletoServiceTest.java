package com.lexia.api.modules.expedientes.coactivas.expediente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CoactivaExpedienteCompletoServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-10-01T15:00:00Z");

  @Test
  void escaneoVigentePrimeroYLuegoLoAgregadoPorFecha() {
    CoactivaArchivo escaneoViejo = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "viejo.pdf", 0);
    CoactivaArchivo unificado = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "juicio.pdf", 1);
    CoactivaArchivo providencia = archivo("PROVIDENCIA", "providencia.pdf", 5);
    CoactivaArchivo actuacion = incorporada(archivo("ACTUACION", "ratificacion.docx", 3), 3);
    CoactivaArchivo acta = archivo(CoactivaArchivo.ACTA, "acta.pdf", 2);

    List<CoactivaArchivo> orden =
        CoactivaExpedienteCompletoService.ordenar(List.of(providencia, acta, actuacion, unificado, escaneoViejo));

    assertThat(orden).containsExactly(unificado, actuacion, providencia);
  }

  @Test
  void actuacionNoAgregadaQuedaFuera() {
    CoactivaArchivo unificado = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "juicio.pdf", 0);
    CoactivaArchivo pendiente = archivo("ACTUACION", "avoco.pdf", 1);
    CoactivaArchivo escrito = archivo("ESCRITO", "escrito.pdf", 2);

    assertThat(CoactivaExpedienteCompletoService.ordenar(List.of(unificado, pendiente, escrito)))
        .containsExactly(unificado, escrito);
  }

  @Test
  void actuacionesVanEnElOrdenEnQueSeAgregaron() {
    CoactivaArchivo unificado = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "juicio.pdf", 0);
    CoactivaArchivo primera = incorporada(archivo("ACTUACION", "avoco.pdf", 1), 6);
    CoactivaArchivo segunda = incorporada(archivo("ACTUACION", "embargo.pdf", 2), 4);
    CoactivaArchivo providencia = archivo("PROVIDENCIA", "providencia.pdf", 5);

    assertThat(CoactivaExpedienteCompletoService.ordenar(List.of(primera, segunda, providencia, unificado)))
        .containsExactly(unificado, segunda, providencia, primera);
  }

  @Test
  void incorporarDosVecesConservaLaPrimeraFecha() {
    CoactivaArchivo actuacion = archivo("ACTUACION", "avoco.pdf", 0);
    actuacion.incorporar(UUID.randomUUID());
    Instant primera = actuacion.getIncorporadoAt();

    actuacion.incorporar(UUID.randomUUID());

    assertThat(actuacion.incorporado()).isTrue();
    assertThat(actuacion.getIncorporadoAt()).isEqualTo(primera);
  }

  @Test
  void sinUnificadoUsaElEscaneadoMasReciente() {
    CoactivaArchivo viejo = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "viejo.pdf", 0);
    CoactivaArchivo nuevo = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "nuevo.pdf", 4);

    assertThat(CoactivaExpedienteCompletoService.ordenar(List.of(viejo, nuevo))).containsExactly(nuevo);
  }

  @Test
  void tituloLlevaTipoNombreYFecha() {
    assertThat(CoactivaExpedienteCompletoService.titulo(archivo("ACTUACION", "ratificacion.pdf", 0)))
        .isEqualTo("Actuación: ratificacion.pdf (01/10/2026)");
  }

  @Test
  void unirConservaElOrdenYDejaUnMarcadorPorPieza() throws Exception {
    List<CoactivaExpedienteCompletoService.Pieza> piezas =
        List.of(
            new CoactivaExpedienteCompletoService.Pieza("Expediente: juicio.pdf", pdf(3)),
            new CoactivaExpedienteCompletoService.Pieza("Actuación: ratificacion.pdf", pdf(1)),
            new CoactivaExpedienteCompletoService.Pieza("Providencia: p.pdf", pdf(2)));

    byte[] unido = CoactivaExpedienteCompletoService.unir(piezas);

    try (PDDocument doc = Loader.loadPDF(unido)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(6);
      List<String> titulos = new ArrayList<>();
      List<Integer> paginas = new ArrayList<>();
      for (PDOutlineItem item : doc.getDocumentCatalog().getDocumentOutline().children()) {
        titulos.add(item.getTitle());
        paginas.add(doc.getPages().indexOf(item.findDestinationPage(doc)));
      }
      assertThat(titulos).containsExactly("Expediente: juicio.pdf", "Actuación: ratificacion.pdf", "Providencia: p.pdf");
      assertThat(paginas).containsExactly(0, 3, 4);
    }
  }

  @Test
  void imagenSeConvierteEnUnaPaginaA4() throws Exception {
    BufferedImage img = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    ImageIO.write(img, "png", png);

    byte[] pdf = CoactivaExpedienteCompletoService.imagenAPdf(png.toByteArray(), "oficio.png");

    try (PDDocument doc = Loader.loadPDF(pdf)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(1);
    }
  }

  @Test
  void formatoNoSoportadoSeRechaza() {
    CoactivaExpedienteCompletoService service = new CoactivaExpedienteCompletoService(null, null, null, null, null);
    CoactivaArchivo zip = CoactivaArchivo.create(UUID.randomUUID(), TENANT, "OTRO", "anexos.zip", "application/zip", 1, "x", "p", null);

    assertThatThrownBy(() -> service.aPdf(zip, new byte[] {1})).hasMessageContaining("no soportado");
  }

  private static CoactivaArchivo archivo(String tipo, String nombre, int dias) {
    String mime = nombre.endsWith(".docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/pdf";
    CoactivaArchivo a = CoactivaArchivo.create(UUID.randomUUID(), TENANT, tipo, nombre, mime, 1, "sha", "ruta", null);
    ReflectionTestUtils.setField(a, "createdAt", T0.plusSeconds(86_400L * dias));
    return a;
  }

  private static CoactivaArchivo incorporada(CoactivaArchivo a, int dias) {
    ReflectionTestUtils.setField(a, "incorporadoAt", T0.plusSeconds(86_400L * dias));
    return a;
  }

  private static byte[] pdf(int paginas) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < paginas; i++) {
        doc.addPage(new PDPage());
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }
}
