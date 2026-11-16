package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MinutaViviendaDataCifraTest {

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "120000         | 120.000,00",
        "120000.5       | 120.000,50",
        "1234567.89     | 1.234.567,89",
        "0              | 0,00",
        "USD 95000      | 95.000,00",
        "120.000        | 120.000,00",
        "1.500          | 1.500,00",
        "$ 85.000,50    | 85.000,50",
        "85,000.00      | 85.000,00",
        "$85,000.00     | 85.000,00",
        "45000.00       | 45.000,00",
      })
  void formateaMilesConPuntoYDecimalesConComa(String entrada, String esperado) {
    assertEquals(esperado, MinutaViviendaData.cifra("monto_prestamo", entrada));
  }

  @Test
  void noNumericoSeDevuelveTalCual() {
    assertEquals("ochenta mil", MinutaViviendaData.cifra("monto_prestamo", "ochenta mil"));
  }

  @Test
  void noNumericoConMonedaNoDuplicaElUsdDeLaPlantilla() {
    assertEquals("ochenta mil", MinutaViviendaData.cifra("monto_prestamo", "USD ochenta mil"));
  }

  @Test
  void vacioSigueSiendoDatoAusente() {
    MinutaViviendaData data = new MinutaViviendaData();
    data.setMontoPrestamo("");

    assertEquals("nodata", data.toTemplateMap().get("monto_prestamo"));
  }

  @Test
  void digitosConLetrasConcatenaNumeroYLectura() {
    assertEquals(
        "0954656732 (CERO NUEVE CINCO CUATRO SEIS CINCO SEIS SIETE TRES DOS)",
        MinutaViviendaData.digitosConLetras("0954656732"));
    assertEquals("nodata", MinutaViviendaData.digitosConLetras(""));
    assertEquals("abc", MinutaViviendaData.digitosConLetras("abc"));
  }

  @Test
  void elLocaleDelServidorNoCambiaElResultado() {
    Locale anterior = Locale.getDefault();
    try {
      Locale.setDefault(Locale.US);
      assertEquals("1.234.567,89", MinutaViviendaData.cifra("monto_prestamo", "1234567.89"));
      Locale.setDefault(Locale.GERMANY);
      assertEquals("120.000,50", MinutaViviendaData.cifra("monto_prestamo", "120000.5"));
    } finally {
      Locale.setDefault(anterior);
    }
  }

  @Test
  void todosLosCamposDeDineroDelMapaUsanElFormato() {
    MinutaViviendaData data = new MinutaViviendaData();
    data.setMontoPrestamo("120000");
    data.setCuotaCredito("410.2");
    data.setAvaluoInmueble("$ 150.000");
    data.setPrecioCompraventaNumero("95000");
    data.setValorEntradaNumero("15,000.00");
    data.setSaldoCompraventaNumero("80000");

    var mapa = data.toTemplateMap();

    assertEquals("120.000,00", mapa.get("monto_prestamo"));
    assertEquals("410,20", mapa.get("cuota_credito"));
    assertEquals("150.000,00", mapa.get("avaluo_inmueble"));
    assertEquals("95.000,00", mapa.get("precio_compraventa_numero"));
    assertEquals("15.000,00", mapa.get("valor_entrada_numero"));
    assertEquals("80.000,00", mapa.get("saldo_compraventa_numero"));
  }
}
