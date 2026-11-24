package com.lexia.api.modules.expedientes.minutas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Precio de venta y anticipos del expediente. Entrada, saldo y el literal de forma de pago se
 * calculan aquí; no se capturan a mano.
 */
public final class PrecioFormaPago {

  public static final String PRECIO = "precio_compraventa_numero";
  public static final String ENTRADA = "valor_entrada_numero";
  public static final String SALDO = "saldo_compraventa_numero";
  /** Anticipos + saldo BIESS: plantillas que solo dicen «que serán cancelados de la siguiente manera:». */
  public static final String DETALLE = "detalle_forma_pago";
  /**
   * Solo los anticipos, a media frase: plantillas que ya redactan entrada y saldo («(USD entrada),
   * {{detalle_entrada}}, y el valor restante…»).
   */
  public static final String DETALLE_ENTRADA = "detalle_entrada";
  public static final List<String> CAPA = List.of(PRECIO, ENTRADA, SALDO, DETALLE, DETALLE_ENTRADA);

  /** Números y letras: los corrige el apartado, no el panel de la minuta. */
  public static final List<String> SOLO_LECTURA =
      List.of(
          PRECIO,
          "precio_compraventa_letras",
          ENTRADA,
          "valor_entrada_letras",
          SALDO,
          "saldo_compraventa_letras");

  private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/uuuu");
  private static final int MAX_PAGOS = 30;
  private static final int MAX_DESCRIPCION = 240;

  private PrecioFormaPago() {}

  public enum Forma {
    TRANSFERENCIA,
    EFECTIVO,
    CHEQUE,
    OTRO
  }

  public record Pago(Forma forma, BigDecimal valor, LocalDate fecha, String descripcion) {}

  public record Calculado(
      BigDecimal precio,
      BigDecimal entrada,
      BigDecimal saldo,
      String detalle,
      String detalleEntrada,
      String aviso) {

    public Map<String, String> capa() {
      Map<String, String> out = new LinkedHashMap<>();
      out.put(PRECIO, plain(precio));
      out.put(ENTRADA, plain(entrada));
      out.put(SALDO, plain(saldo));
      out.put(DETALLE, detalle);
      out.put(DETALLE_ENTRADA, detalleEntrada);
      return out;
    }
  }

  public static boolean tienePrecio(Map<String, String> capa) {
    if (capa == null) {
      return false;
    }
    return NumeroALetras.parsearMonto(capa.get(PRECIO)).filter(v -> v.signum() > 0).isPresent();
  }

  public static Calculado calcular(BigDecimal precio, List<Pago> pagos, BigDecimal montoPrestamo) {
    if (precio == null || precio.signum() <= 0) {
      throw new IllegalArgumentException("Indica el precio de venta.");
    }
    BigDecimal venta = precio.setScale(2, RoundingMode.HALF_UP);
    List<Pago> lista = pagos == null ? List.of() : pagos;
    if (lista.size() > MAX_PAGOS) {
      throw new IllegalArgumentException("Hay demasiados pagos anticipados.");
    }
    BigDecimal entrada = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    List<String> lineas = new ArrayList<>();
    List<String> medios = new ArrayList<>();
    for (Pago pago : lista) {
      if (pago == null || pago.forma == null || pago.fecha == null) {
        throw new IllegalArgumentException("Cada pago necesita forma, valor y fecha.");
      }
      if (pago.valor == null || pago.valor.signum() <= 0) {
        throw new IllegalArgumentException("El valor de cada pago tiene que ser mayor que cero.");
      }
      String descripcion = pago.descripcion == null ? "" : pago.descripcion.trim();
      if (descripcion.length() > MAX_DESCRIPCION) {
        throw new IllegalArgumentException("La descripción del pago es demasiado larga.");
      }
      BigDecimal valor = pago.valor.setScale(2, RoundingMode.HALF_UP);
      entrada = entrada.add(valor);
      String medio = medio(pago.forma, pago.fecha, descripcion);
      medios.add(medio);
      lineas.add(montoLiteral(valor) + ", " + medio);
    }
    String detalleEntrada =
        switch (lineas.size()) {
          case 0 -> "sin pagos anticipados";
          case 1 -> "pagados " + medios.get(0);
          default -> "pagados de la siguiente manera: " + enumerar(lineas);
        };
    if (entrada.compareTo(venta) > 0) {
      throw new IllegalArgumentException("Los pagos anticipados superan el precio de venta.");
    }
    BigDecimal saldo = venta.subtract(entrada);
    if (saldo.signum() > 0) {
      lineas.add(lineaSaldo(saldo));
    }
    String aviso = null;
    if (montoPrestamo != null && saldo.compareTo(montoPrestamo.setScale(2, RoundingMode.HALF_UP)) != 0) {
      aviso =
          "El saldo ("
              + cifra(saldo)
              + ") no coincide con el monto del préstamo BIESS ("
              + cifra(montoPrestamo)
              + ").";
    }
    return new Calculado(venta, entrada, saldo, literales(lineas), detalleEntrada, aviso);
  }

  /**
   * Continúa «que serán cancelados de la siguiente manera:». Un solo concepto va sin letra; varios
   * van como «a) …; b) …; y, c) …».
   */
  static String literales(List<String> lineas) {
    if (lineas.size() == 1) {
      return lineas.get(0) + ".";
    }
    return enumerar(lineas) + ".";
  }

  /** «a) …; b) …; y, c) …» sin punto final (puede ir a media frase). */
  static String enumerar(List<String> lineas) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lineas.size(); i++) {
      if (i > 0) {
        out.append(i == lineas.size() - 1 ? "; y, " : "; ");
      }
      out.append(letra(i)).append(") ").append(lineas.get(i));
    }
    return out.toString();
  }

  /** a … z, luego aa, ab …: MAX_PAGOS más el saldo pasa de 26. */
  private static String letra(int i) {
    String letra = String.valueOf((char) ('a' + i % 26));
    return i < 26 ? letra : (char) ('a' + i / 26 - 1) + letra;
  }

  private static String medio(Forma forma, LocalDate fecha, String descripcion) {
    String dia = FECHA.format(fecha);
    String medio =
        switch (forma) {
          case TRANSFERENCIA -> "mediante transferencia bancaria realizada el " + dia;
          case EFECTIVO -> "en dinero en efectivo entregado el " + dia;
          case CHEQUE -> "mediante cheque entregado el " + dia;
          case OTRO ->
              "mediante " + (descripcion.isEmpty() ? "otro medio de pago" : descripcion) + ", con fecha " + dia;
        };
    String extra = forma != Forma.OTRO && !descripcion.isEmpty() ? " (" + descripcion + ")" : "";
    return medio + extra;
  }

  private static String lineaSaldo(BigDecimal saldo) {
    return montoLiteral(saldo)
        + ", que corresponde al saldo del precio, con el préstamo hipotecario que el Banco del"
        + " Instituto Ecuatoriano de Seguridad Social, BIESS, otorga a LA PARTE COMPRADORA";
  }

  static String montoLiteral(BigDecimal valor) {
    return "USD " + cifra(valor) + " (" + NumeroALetras.montoBancario(valor) + " DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA)";
  }

  static String cifra(BigDecimal valor) {
    DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
    simbolos.setGroupingSeparator('.');
    simbolos.setDecimalSeparator(',');
    DecimalFormat formato = new DecimalFormat("#,##0.00", simbolos);
    formato.setRoundingMode(RoundingMode.HALF_UP);
    return formato.format(valor);
  }

  private static String plain(BigDecimal valor) {
    return valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
