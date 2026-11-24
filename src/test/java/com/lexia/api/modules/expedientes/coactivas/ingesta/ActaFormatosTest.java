package com.lexia.api.modules.expedientes.coactivas.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Campo;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Resultado;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaColumnas;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActaFormatosTest {

  /** Formato de un delegado con nombres que las palabras clave no reconocen. */
  private static final List<String> CABECERA_RARA = List.of("Nº", "Expediente N°", "Nombres completos", "Doc. Id.", "Hojas");

  private static final List<List<List<String>>> ACTA_RARA =
      List.of(
          List.of(
              List.of("ACTA DE ENTREGA - DELEGADO PORTOVIEJO", "", "", "", ""),
              CABECERA_RARA,
              List.of("1", "025-2024-00026", "PEREZ JUAN", "0912345678", "45"),
              List.of("2", "025-2024-00027", "LOPEZ ANA", "1312345678", "12")));

  @Test
  void laCabeceraRaraNoSeReconocePorPalabras() {
    Resultado r = ActaTableParser.interpretar(ACTA_RARA);

    assertThat(r.advertencias()).anyMatch(a -> a.contains("sin cabecera reconocible"));
    assertThat(r.filas()).allMatch(f -> f.fojas() == null);
  }

  @Test
  void mapeoManualSacaTodasLasFilas() {
    Map<Campo, Integer> mapeo = ActaFormatos.validarMapeo(Map.of(1, "JUICIO", 2, "DEUDOR", 3, "CEDULA", 4, "FOJAS"), 5);
    String firma = ActaTableParser.firma(CABECERA_RARA);

    Resultado r = ActaTableParser.interpretar(ACTA_RARA, ActaFormatos.exacto(firma, mapeo));

    assertThat(r.filas()).hasSize(2);
    assertThat(r.filas().get(0).juicio()).isEqualTo("025-2024-00026");
    assertThat(r.filas().get(0).deudor()).isEqualTo("PEREZ JUAN");
    assertThat(r.filas().get(0).cedula()).isEqualTo("0912345678");
    assertThat(r.filas().get(1).fojas()).isEqualTo(12);
  }

  @Test
  void formatoGuardadoSeAplicaSoloYAvisaLaFirma() {
    Map<Campo, Integer> mapeo = ActaFormatos.validarMapeo(Map.of(1, "JUICIO", 2, "DEUDOR"), 5);
    String firma = ActaTableParser.firma(CABECERA_RARA);
    Map<String, ActaFormatos.Guardado> guardados = Map.of(firma, new ActaFormatos.Guardado("Portoviejo", mapeo));
    List<String> aplicadas = new ArrayList<>();

    Resultado r = ActaTableParser.interpretar(ACTA_RARA, ActaFormatos.conGuardados(guardados, aplicadas::add));

    assertThat(r.filas()).hasSize(2).allMatch(f -> f.juicio() != null && f.deudor() != null);
    assertThat(aplicadas).containsExactly(firma);
  }

  @Test
  void sinFormatoGuardadoSigueUsandoPalabrasClave() {
    List<List<List<String>>> estandar =
        List.of(List.of(List.of("JUICIO", "DEUDOR", "CEDULA"), List.of("025-2024-00026", "PEREZ JUAN", "0912345678")));
    List<String> aplicadas = new ArrayList<>();

    Resultado r = ActaTableParser.interpretar(estandar, ActaFormatos.conGuardados(Map.of(), aplicadas::add));

    assertThat(r.filas()).hasSize(1);
    assertThat(aplicadas).isEmpty();
  }

  @Test
  void laFirmaIgnoraMayusculasTildesYCeldasVaciasAlFinal() {
    assertThat(ActaTableParser.firma(List.of("Juicio", "Cédula", "", "")))
        .isEqualTo(ActaTableParser.firma(List.of("JUICIO", "CEDULA")))
        .hasSize(64);
    assertThat(ActaTableParser.firma(List.of("", " "))).isNull();
    assertThat(ActaTableParser.firma(List.of("A", "B"))).isNotEqualTo(ActaTableParser.firma(List.of("B", "A")));
  }

  @Test
  void validarMapeoRechazaErrores() {
    assertThatThrownBy(() -> ActaFormatos.validarMapeo(Map.of(0, "CEDULA"), 3))
        .hasMessageContaining("juicio o la del deudor");
    assertThatThrownBy(() -> ActaFormatos.validarMapeo(Map.of(0, "JUICIO", 1, "JUICIO"), 3))
        .hasMessageContaining("dos columnas");
    assertThatThrownBy(() -> ActaFormatos.validarMapeo(Map.of(7, "JUICIO"), 3)).hasMessageContaining("no existe");
    assertThatThrownBy(() -> ActaFormatos.validarMapeo(Map.of(0, "NOPE"), 3)).hasMessageContaining("desconocido");
    Map<Integer, String> conIgnorar = new HashMap<>();
    conIgnorar.put(0, "JUICIO");
    conIgnorar.put(1, "IGNORAR");
    conIgnorar.put(2, null);
    assertThat(ActaFormatos.validarMapeo(conIgnorar, 3)).containsOnlyKeys(Campo.JUICIO);
  }

  @Test
  void mapeoIdaYVueltaPorJson() {
    Map<Campo, Integer> mapeo = new EnumMap<>(Campo.class);
    mapeo.put(Campo.JUICIO, 1);
    mapeo.put(Campo.DEUDOR, 2);

    assertThat(ActaFormatos.mapeoDesdeJson(ActaFormatos.mapeoAJson(mapeo))).isEqualTo(mapeo);
    assertThat(ActaFormatos.mapeoDesdeJson("basura")).isEmpty();
  }

  @Test
  void tablasIdaYVueltaPorJson() {
    String json = ActaFormatos.serializar(ACTA_RARA);

    assertThat(ActaFormatos.deserializar(json)).isEqualTo(ACTA_RARA);
    assertThat(ActaFormatos.deserializar(null)).isEmpty();
  }

  @Test
  void columnasSugiereCampoYMuestraEjemplos() {
    ActaColumnas vista = ActaFormatos.columnas(ACTA_RARA, null, 1, Map.of(), true);

    assertThat(vista.filaCabecera()).isEqualTo(1);
    assertThat(vista.columnas()).hasSize(5);
    assertThat(vista.columnas().get(1).titulo()).isEqualTo("Expediente N°");
    assertThat(vista.columnas().get(1).ejemplos()).containsExactly("025-2024-00026", "025-2024-00027");
    assertThat(vista.columnas().get(2).campo()).isEqualTo("DEUDOR");
    assertThat(vista.columnas().get(4).campo()).isNull();
    assertThat(vista.filas()).hasSize(4);
    assertThat(vista.editable()).isTrue();
  }

  @Test
  void columnasUsaElFormatoGuardadoSiCoincide() {
    Map<Campo, Integer> mapeo = ActaFormatos.validarMapeo(Map.of(1, "JUICIO", 2, "DEUDOR"), 5);
    Map<String, ActaFormatos.Guardado> guardados =
        Map.of(ActaTableParser.firma(CABECERA_RARA), new ActaFormatos.Guardado("Portoviejo", mapeo));

    ActaColumnas vista = ActaFormatos.columnas(ACTA_RARA, 0, 1, guardados, false);

    assertThat(vista.formatoNombre()).isEqualTo("Portoviejo");
    assertThat(vista.columnas().get(1).campo()).isEqualTo("JUICIO");
    assertThat(vista.columnas().get(2).campo()).isEqualTo("DEUDOR");
    assertThat(vista.columnas().get(4).campo()).isNull();
  }

  @Test
  void columnasRechazaTablaOFilaFueraDeRango() {
    assertThatThrownBy(() -> ActaFormatos.columnas(ACTA_RARA, 3, null, Map.of(), true)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActaFormatos.columnas(ACTA_RARA, 0, 9, Map.of(), true)).isInstanceOf(IllegalArgumentException.class);
  }
}
