package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lexia.api.modules.notary.LetrasNotariales.Tipo;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LetrasNotarialesTest {

  private static String convertir(Tipo tipo, String valor) {
    return LetrasNotariales.convertir(tipo, valor, "").orElseThrow();
  }

  @Test
  void cedulaRucYTelefonoDigitoPorDigitoConservandoElGuion() {
    assertEquals(
        "cero nueve dos dos cinco cinco dos cuatro ocho – cuatro (092255248-4)",
        convertir(Tipo.DIGITOS, "092255248-4"));
    assertEquals(
        "cero nueve dos dos cinco cinco dos cuatro ocho cuatro (0922552484)",
        convertir(Tipo.DIGITOS, " 0922552484 "));
    assertEquals(
        "cero nueve nueve dos tres cuatro cinco seis siete ocho cero cero uno (0992345678001)",
        convertir(Tipo.DIGITOS, "0992345678001"));
    assertEquals(
        "más cinco nueve tres nueve nueve uno dos tres cuatro cinco seis siete (+593 99 123 4567)",
        convertir(Tipo.DIGITOS, "+593 99 123 4567"));
  }

  @Test
  void montoConLasLetrasDelPayloadEnMayusculas() {
    assertEquals(
        "CIENTO VEINTE MIL CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD 120.000,00)",
        LetrasNotariales.convertir(Tipo.MONTO, "120.000,00", "ciento veinte mil").orElseThrow());
    assertEquals(
        "CIENTO VEINTE MIL CON 50/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD 120.000,50)",
        LetrasNotariales.convertir(
                Tipo.MONTO, "120000.50", "ciento veinte mil con 50/100 dólares americanos")
            .orElseThrow());
  }

  @Test
  void montoSinLetrasEnElPayloadLasCalcula() {
    assertEquals(
        "OCHENTA Y CINCO MIL CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD 85.000,00)",
        convertir(Tipo.MONTO, "85.000,00"));
    assertEquals(
        "VEINTIÚN MIL CON 05/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD 21.000,05)",
        convertir(Tipo.MONTO, "USD 21.000,05"));
  }

  @Test
  void superficieEnMetrosYDecimetrosCuadrados() {
    assertEquals(
        "ochenta y cuatro metros cuadrados (84,00 m²)", convertir(Tipo.SUPERFICIE, "84"));
    assertEquals(
        "ochenta metros cuadrados con ochenta decímetros cuadrados (80,80 m²)",
        convertir(Tipo.SUPERFICIE, "80,80 m2"));
    assertEquals("veintiún metros cuadrados (21,00 m²)", convertir(Tipo.SUPERFICIE, "21 m²"));
    assertEquals(
        "un metro cuadrado con un decímetro cuadrado (1,01 m²)", convertir(Tipo.SUPERFICIE, "1.01"));
  }

  @Test
  void claveCatastralSegmentoPorSegmento() {
    assertEquals(
        "sesenta – dos mil trescientos ochenta y siete – cero trece – cero – cero – cero"
            + " (60 – 2387 – 013 – 0 – 0 - 0)",
        convertir(Tipo.CLAVE_CATASTRAL, "60 – 2387 – 013 – 0 – 0 - 0"));
    assertEquals("veintiuno – cero uno (21.01)", convertir(Tipo.CLAVE_CATASTRAL, "21.01"));
    assertEquals("cero uno dos (012)", convertir(Tipo.CLAVE_CATASTRAL, "012"));
  }

  @Test
  void fechaConPrimeroYCardinalesSinApocope() {
    assertEquals(
        "treinta y uno de marzo de dos mil veintiséis (31/03/2026)",
        convertir(Tipo.FECHA, "31/03/2026"));
    assertEquals(
        "primero de agosto de dos mil veintiséis (01/08/2026)", convertir(Tipo.FECHA, "2026-08-01"));
    assertEquals(
        "veintiuno de enero de dos mil veintiuno (21/01/2021)", convertir(Tipo.FECHA, "21-1-2021"));
  }

  @Test
  void fechaDeOtorgamientoComoElTextoDeLaEscritura() {
    assertEquals(
        "a los veinte días del mes de agosto de dos mil veintiséis",
        convertir(Tipo.FECHA_OTORGAMIENTO, "2026-08-20"));
    assertEquals(
        "a los veintiún días del mes de agosto de dos mil veintiuno",
        convertir(Tipo.FECHA_OTORGAMIENTO, "21/08/2021"));
    assertEquals(
        "al primer día del mes de agosto de dos mil veintiséis",
        convertir(Tipo.FECHA_OTORGAMIENTO, "2026-08-01T00:00:00Z"));
  }

  @Test
  void cardinalSinApocope() {
    assertEquals("uno", LetrasNotariales.cardinal(1));
    assertEquals("veintiuno", LetrasNotariales.cardinal(21));
    assertEquals("treinta y uno", LetrasNotariales.cardinal(31));
    assertEquals("ciento uno", LetrasNotariales.cardinal(101));
    assertEquals("dos mil veintiuno", LetrasNotariales.cardinal(2021));
    assertEquals("veintiún mil", LetrasNotariales.cardinal(21_000));
  }

  @Test
  void valoresNoInterpretablesNoSeConvierten() {
    assertThat(LetrasNotariales.convertir(Tipo.DIGITOS, "0922/552484", "")).isEmpty();
    assertThat(LetrasNotariales.convertir(Tipo.MONTO, "12,3,4", "")).isEmpty();
    assertThat(LetrasNotariales.convertir(Tipo.FECHA, "31/02/2026", "")).isEmpty();
    assertThat(LetrasNotariales.convertir(Tipo.SUPERFICIE, "84 m2 aprox.", "")).isEmpty();
    assertThat(LetrasNotariales.convertir(Tipo.CLAVE_CATASTRAL, "60_2387", "")).isEmpty();
    assertThat(LetrasNotariales.parsearFecha("")).isEmpty();
    assertEquals(Optional.of(LocalDate.of(2026, 8, 20)), LetrasNotariales.parsearFecha("20.08.2026"));
  }

  @Test
  void completarSoloLoQuePideLaPlantilla() {
    Map<String, Object> datos = new LinkedHashMap<>();
    datos.put("cedula_conyuge_1", "0922552484");
    datos.put("cedula_conyuge_2", "0922552484");

    LetrasNotariales.Resultado resultado =
        LetrasNotariales.completar(datos, List.of("cedula_conyuge_1_completo", "cedula_conyuge_1"));

    assertThat(resultado.completos()).containsOnlyKeys("cedula_conyuge_1_completo");
    assertThat(resultado.sinConvertir()).isEmpty();
  }

  @Test
  void vacioNoSeCompletaYConLetrasQuedaTalCual() {
    Map<String, Object> datos = new LinkedHashMap<>();
    datos.put("superficie_m2", "ochenta y cuatro metros cuadrados (84,00m²)");
    datos.put("telefono_deudor", "  ");
    datos.put("fecha_otorgamiento", "veinte días del mes de agosto de dos mil veintiséis");

    LetrasNotariales.Resultado resultado =
        LetrasNotariales.completar(
            datos,
            List.of(
                "superficie_m2_completo",
                "telefono_deudor_completo",
                "clave_catastral_completo",
                "fecha_otorgamiento_completo"));

    assertThat(resultado.completos())
        .containsEntry("superficie_m2_completo", "ochenta y cuatro metros cuadrados (84,00m²)")
        .containsEntry(
            "fecha_otorgamiento_completo",
            "a los veinte días del mes de agosto de dos mil veintiséis")
        .doesNotContainKeys("telefono_deudor_completo", "clave_catastral_completo");
    assertThat(resultado.sinConvertir()).isEmpty();
  }

  @Test
  void noInterpretableSinLetrasQuedaTalCualYSeReporta() {
    Map<String, Object> datos = new LinkedHashMap<>();
    datos.put("cedula_conyuge_1", "0922/552484");
    datos.put("monto_prestamo", "12,3,4");
    datos.put("fecha_escritura_antecedente", "31/02/2026");

    LetrasNotariales.Resultado resultado =
        LetrasNotariales.completar(
            datos,
            List.of(
                "cedula_conyuge_1_completo",
                "monto_prestamo_completo",
                "fecha_escritura_antecedente_completo"));

    assertThat(resultado.completos())
        .containsEntry("cedula_conyuge_1_completo", "0922/552484")
        .containsEntry("monto_prestamo_completo", "USD 12,3,4")
        .containsEntry("fecha_escritura_antecedente_completo", "31/02/2026");
    assertThat(resultado.sinConvertir())
        .containsExactly("cedula_conyuge_1", "monto_prestamo", "fecha_escritura_antecedente");
  }

  @Test
  void origenDelDerivado() {
    assertEquals("clave_catastral", LetrasNotariales.origen("clave_catastral_completo"));
    assertEquals("nombre_vendedor_completo", LetrasNotariales.origen("nombre_vendedor_completo"));
    assertEquals("clave_catastral", LetrasNotariales.origen("clave_catastral"));
  }
}
