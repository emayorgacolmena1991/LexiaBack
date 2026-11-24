package com.lexia.api.modules.expedientes.coactivas.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.FilaActa;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Resultado;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ActaIaExtractorTest {

  private static final List<List<List<String>>> TABLAS =
      List.of(
          List.of(
              List.of("Nro", "Causa N°", "Coactivado", "Doc."),
              List.of("1", "025-2024-00026", "PEREZ JUAN", "0912345678")));

  private static final String JSON_IA =
      """
      {"uec": "Portoviejo",
       "filas": [
         {"juicio": "025-2024-00026", "deudor": "PEREZ JUAN", "cedula": "0912345678", "fojas": 45},
         {"juicio": "025-2024-00027", "deudor": "LOPEZ ANA", "oficina": "Chone", "fojas": "12"},
         {"juicio": null, "deudor": "TOTAL"}
       ]}
      """;

  @Test
  void cabeceraReconocidaNoLlamaALaIa() {
    LlmFalso llm = new LlmFalso(ValidacionDocumentoJson.ok(JSON_IA));
    Resultado bueno = resultado(fila("025-2024-00026", "PEREZ JUAN"), fila("025-2024-00027", "LOPEZ ANA"));

    Resultado r = extractor(llm, true).mejorar(bueno, TABLAS, null);

    assertThat(r).isSameAs(bueno);
    assertThat(llm.llamadas.get()).isZero();
  }

  @Test
  void sinFilasUsaLaIaYMarcaElAviso() {
    LlmFalso llm = new LlmFalso(ValidacionDocumentoJson.ok(JSON_IA));

    Resultado r = extractor(llm, true).mejorar(resultado(), TABLAS, "UEC: Portoviejo");

    assertThat(llm.llamadas.get()).isEqualTo(1);
    assertThat(r.filas()).hasSize(2);
    assertThat(r.filas().get(0).juicio()).isEqualTo("025-2024-00026");
    assertThat(r.filas().get(0).fojas()).isEqualTo(45);
    assertThat(r.filas().get(1).oficina()).isEqualTo("Chone");
    assertThat(r.filas().get(1).fojas()).isEqualTo(12);
    assertThat(r.uec()).isEqualTo("Portoviejo");
    assertThat(r.advertencias()).containsExactly(ActaIaExtractor.AVISO_IA);
  }

  @Test
  void pocasFilasCompletasDisparanLaIa() {
    Resultado flojo = resultado(fila("025-2024-00026", null), fila("025-2024-00027", null), fila("025-2024-00028", "X"));

    assertThat(ActaIaExtractor.necesitaRespaldo(flojo)).isTrue();
    assertThat(ActaIaExtractor.necesitaRespaldo(resultado(fila("025-2024-00026", "A")))).isFalse();
  }

  @Test
  void deteccionSoloPorPatronDisparaLaIa() {
    Resultado heuristico =
        new Resultado(
            List.of(fila("025-2024-00026", "A")),
            null,
            List.of("Tabla 1: sin cabecera reconocible; se detectaron juicios por patrón. Revisa las filas."));

    assertThat(ActaIaExtractor.necesitaRespaldo(heuristico)).isTrue();
  }

  @Test
  void siLaIaFallaSeQuedaElResultadoOriginalConAviso() {
    LlmFalso llm = new LlmFalso(ValidacionDocumentoJson.error("HTTP 529"));
    Resultado vacio = resultado();

    Resultado r = extractor(llm, true).mejorar(vacio, TABLAS, null);

    assertThat(r.filas()).isEmpty();
    assertThat(r.advertencias()).contains(ActaIaExtractor.AVISO_IA_FALLO);
  }

  @Test
  void siLaIaNoMejoraSeQuedaElResultadoOriginal() {
    LlmFalso llm = new LlmFalso(ValidacionDocumentoJson.ok("{\"filas\": [{\"juicio\": \"025-2024-00026\"}]}"));
    Resultado original =
        new Resultado(
            List.of(fila("025-2024-00026", "A"), fila("025-2024-00027", "B")),
            null,
            List.of("Tabla 1: sin cabecera reconocible; se detectaron juicios por patrón. Revisa las filas."));

    Resultado r = extractor(llm, true).mejorar(original, TABLAS, null);

    assertThat(r.filas()).isEqualTo(original.filas());
    assertThat(r.advertencias()).contains(ActaIaExtractor.AVISO_IA_FALLO);
  }

  @Test
  void deshabilitadoNoLlamaALaIa() {
    LlmFalso llm = new LlmFalso(ValidacionDocumentoJson.ok(JSON_IA));

    Resultado r = extractor(llm, false).mejorar(resultado(), TABLAS, null);

    assertThat(llm.llamadas.get()).isZero();
    assertThat(r.filas()).isEmpty();
  }

  @Test
  void jsonInvalidoNoLanza() {
    assertThat(ActaIaExtractor.parsear("no es json")).isEmpty();
    assertThat(ActaIaExtractor.parsear("{\"otra\": 1}")).isEmpty();
    assertThat(ActaIaExtractor.parsear(null)).isEmpty();
  }

  @Test
  void entradaIncluyeTablasYTexto() {
    String entrada = ActaIaExtractor.entrada(TABLAS, "ACTA DE ENTREGA UEC Portoviejo");

    assertThat(entrada).contains("<tabla n=\"1\">").contains("025-2024-00026\tPEREZ JUAN").contains("<texto_ocr>");
  }

  private static ActaIaExtractor extractor(LlmFalso llm, boolean habilitado) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("llm", llm);
    return new ActaIaExtractor(beans.getBeanProvider(AnalisisDocumentoService.class), habilitado);
  }

  private static Resultado resultado(FilaActa... filas) {
    return new Resultado(List.of(filas), null, List.of());
  }

  private static FilaActa fila(String juicio, String deudor) {
    return new FilaActa(1, null, null, juicio, deudor, null, null, null);
  }

  private static final class LlmFalso implements AnalisisDocumentoService {
    private final ValidacionDocumentoJson respuesta;
    final AtomicInteger llamadas = new AtomicInteger();

    LlmFalso(ValidacionDocumentoJson respuesta) {
      this.respuesta = respuesta;
    }

    @Override
    public boolean isConfigured() {
      return true;
    }

    @Override
    public ValidacionDocumentoJson extraerActaCoactiva(String systemPrompt, String textoActa) {
      llamadas.incrementAndGet();
      return respuesta;
    }
  }
}
