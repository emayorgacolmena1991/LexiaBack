package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Calculado;
import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Forma;
import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Pago;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrecioFormaPagoTest {

  @Test
  void redactaEntradaSaldoYLiteralDelBufete() {
    Calculado out =
        PrecioFormaPago.calcular(
            new BigDecimal("80500"),
            List.of(
                new Pago(
                    Forma.TRANSFERENCIA,
                    new BigDecimal("500"),
                    LocalDate.of(2026, 10, 12),
                    "")),
            new BigDecimal("80000"));

    assertEquals(new BigDecimal("500.00"), out.entrada());
    assertEquals(new BigDecimal("80000.00"), out.saldo());
    assertEquals(
        "a) USD 500,00 (QUINIENTOS CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA)"
            + " mediante transferencia bancaria realizada el 12/10/2026;"
            + " y, b) USD 80.000,00 (OCHENTA MIL CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA)"
            + " que corresponde al saldo del precio, con el préstamo hipotecario que el Banco del"
            + " Instituto Ecuatoriano de Seguridad Social, BIESS, otorga a LA PARTE COMPRADORA.",
        out.detalle());
    assertEquals(null, out.aviso());
    assertTrue(PrecioFormaPago.tienePrecio(out.capa()));
  }

  @Test
  void pagosQueSuperanElPrecioSonError() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PrecioFormaPago.calcular(
                    new BigDecimal("100"),
                    List.of(
                        new Pago(Forma.EFECTIVO, new BigDecimal("150"), LocalDate.of(2026, 1, 2), "")),
                    null));
    assertTrue(ex.getMessage().contains("superan"));
  }

  @Test
  void saldoDistintoDelPrestamoAvisaSinBloquear() {
    Calculado out =
        PrecioFormaPago.calcular(
            new BigDecimal("1000"),
            List.of(),
            new BigDecimal("900"));
    assertEquals(new BigDecimal("1000.00"), out.saldo());
    assertTrue(out.aviso().contains("no coincide"));
    assertTrue(out.detalle().startsWith("USD 1.000,00 ("));
    assertTrue(out.detalle().endsWith("otorga a LA PARTE COMPRADORA."));
  }

  @Test
  void variosPagosUsanLiteralesYElUltimoLlevaY() {
    Calculado out =
        PrecioFormaPago.calcular(
            new BigDecimal("1000"),
            List.of(
                new Pago(Forma.EFECTIVO, new BigDecimal("100"), LocalDate.of(2026, 1, 2), ""),
                new Pago(Forma.CHEQUE, new BigDecimal("200"), LocalDate.of(2026, 1, 3), "Banco X")),
            null);
    String d = out.detalle();
    assertTrue(d.startsWith("a) USD 100,00 ("), d);
    assertTrue(d.contains("en dinero en efectivo entregado el 02/01/2026; b) USD 200,00 ("), d);
    assertTrue(d.contains("mediante cheque entregado el 03/01/2026 (Banco X); y, c) USD 700,00 ("), d);
    assertEquals(
        "pagados de la siguiente manera: a) USD 100,00 (CIEN CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS"
            + " DE AMÉRICA) en dinero en efectivo entregado el 02/01/2026; y, b) USD 200,00 (DOSCIENTOS"
            + " CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA) mediante cheque entregado el"
            + " 03/01/2026 (Banco X)",
        out.detalleEntrada());
    assertEquals(out.detalleEntrada(), out.capa().get(PrecioFormaPago.DETALLE_ENTRADA));
  }

  @Test
  void detalleEntradaVaAMediaFraseSinSaldo() {
    Calculado uno =
        PrecioFormaPago.calcular(
            new BigDecimal("1000"),
            List.of(new Pago(Forma.TRANSFERENCIA, new BigDecimal("100"), LocalDate.of(2026, 10, 12), "")),
            null);
    assertEquals("pagados mediante transferencia bancaria realizada el 12/10/2026", uno.detalleEntrada());
    Calculado ninguno = PrecioFormaPago.calcular(new BigDecimal("1000"), List.of(), null);
    assertEquals("sin pagos anticipados", ninguno.detalleEntrada());
    assertFalse(ninguno.detalleEntrada().contains("BIESS"));
  }

  @Test
  void sinSaldoNoHayLiteralDelBiess() {
    Calculado out =
        PrecioFormaPago.calcular(
            new BigDecimal("100"),
            List.of(new Pago(Forma.OTRO, new BigDecimal("100"), LocalDate.of(2026, 1, 2), "depósito")),
            null);
    assertEquals(
        "USD 100,00 (CIEN CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA) mediante depósito,"
            + " con fecha 02/01/2026.",
        out.detalle());
    assertFalse(out.detalle().contains("BIESS"));
  }

  @Test
  void literalesPasanDeLaZ() {
    List<String> lineas = java.util.stream.IntStream.range(0, 28).mapToObj(i -> "x" + i).toList();
    String d = PrecioFormaPago.literales(lineas);
    assertTrue(d.contains("; z) x25; aa) x26; y, ab) x27."), d);
  }

  @Test
  void sinPrecioNoHayCapa() {
    assertFalse(PrecioFormaPago.tienePrecio(Map.of()));
    assertFalse(PrecioFormaPago.tienePrecio(null));
  }
}
