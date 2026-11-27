package com.lexia.api.modules.expedientes.cierre;

import com.lexia.api.modules.expedientes.comparecientes.Compareciente;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Datos de la carta de financiamiento ({@code ADICIONALES/carta_financiamiento.docx}): todos los
 * otorgantes de cada parte (sin representantes ni apoderados), NIP, fecha y quién paga cada
 * impuesto. Las firmas van en un solo tag por parte, un bloque por persona.
 */
public final class CartaFinanciamiento {

  public static final String RECURSO = "templates/escrituracion/ADICIONALES/carta_financiamiento.docx";

  static final String LINEA_FIRMA = "__________________________";
  private static final String[] MESES = {
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
  };

  public enum Pagador {
    VENDEDOR,
    CLIENTE,
    FINANCIADO;

    public static Optional<Pagador> de(String codigo) {
      if (codigo == null || codigo.isBlank()) {
        return Optional.empty();
      }
      try {
        return Optional.of(valueOf(codigo.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }
  }

  /** Pagador de cada rubro (null = sin marcar). */
  public record Pagos(Pagador plusvalia, Pagador alcabalas, Pagador obras) {}

  private CartaFinanciamiento() {}

  public static Map<String, Object> datos(
      List<Compareciente> vendedores,
      List<Compareciente> compradores,
      String nip,
      LocalDate fecha,
      Pagos pagos) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("vendedores_identificacion", identificacion(vendedores));
    out.put("compradores_identificacion", identificacion(compradores));
    out.put("firmas_vendedores", firmas(vendedores, "VENDEDOR"));
    out.put("firmas_compradores", firmas(compradores, "COMPRADOR"));
    out.put("nip", nip == null ? "" : nip.trim());
    out.put("fecha_carta", fechaLarga(fecha));
    Pagos p = pagos == null ? new Pagos(null, null, null) : pagos;
    marcas(out, "plusvalia", p.plusvalia());
    marcas(out, "alcabalas", p.alcabalas());
    marcas(out, "obras", p.obras());
    return out;
  }

  /** «A con cédula de ciudadanía No. 0912345678 y B con cédula de ciudadanía No. …». */
  static String identificacion(List<Compareciente> personas) {
    List<String> partes = new ArrayList<>();
    for (Compareciente c : personas) {
      StringBuilder sb = new StringBuilder(nombre(c));
      if (c.getIdentificacion() != null && !c.getIdentificacion().isBlank()) {
        sb.append(c.isPersonaJuridica() ? " con RUC No. " : " con cédula de ciudadanía No. ")
            .append(c.getIdentificacion().trim());
      }
      partes.add(sb.toString());
    }
    return unir(partes);
  }

  /** Un bloque por persona: línea de firma, parte, nombre y cédula. */
  static String firmas(List<Compareciente> personas, String etiqueta) {
    List<String> bloques = new ArrayList<>();
    for (Compareciente c : personas) {
      String id = c.getIdentificacion() == null ? "" : c.getIdentificacion().trim();
      bloques.add(
          LINEA_FIRMA
              + "\n"
              + etiqueta
              + "\n"
              + nombre(c)
              + "\n"
              + (c.isPersonaJuridica() ? "RUC: " : "C.I.: ")
              + id);
    }
    return String.join("\n\n\n", bloques);
  }

  /** «27 de noviembre de 2026». */
  public static String fechaLarga(LocalDate fecha) {
    if (fecha == null) {
      return "";
    }
    return fecha.getDayOfMonth() + " de " + MESES[fecha.getMonthValue() - 1] + " de " + fecha.getYear();
  }

  static String unir(List<String> partes) {
    if (partes.size() <= 1) {
      return partes.isEmpty() ? "" : partes.get(0);
    }
    return String.join(", ", partes.subList(0, partes.size() - 1)) + " y " + partes.get(partes.size() - 1);
  }

  private static String nombre(Compareciente c) {
    return c.getNombres() == null ? "" : c.getNombres().trim().toUpperCase(Locale.ROOT);
  }

  private static void marcas(Map<String, Object> out, String rubro, Pagador pagador) {
    out.put(rubro + "_vendedor", pagador == Pagador.VENDEDOR ? "X" : "");
    out.put(rubro + "_cliente", pagador == Pagador.CLIENTE ? "X" : "");
    out.put(rubro + "_financiado", pagador == Pagador.FINANCIADO ? "X" : "");
  }
}
