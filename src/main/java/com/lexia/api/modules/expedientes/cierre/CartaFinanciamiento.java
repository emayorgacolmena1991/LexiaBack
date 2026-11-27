package com.lexia.api.modules.expedientes.cierre;

import com.lexia.api.modules.expedientes.cierre.PartesCierre.Persona;
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

  /** Sin compradores o sin NIP no se genera; vacío = lista para descargar. */
  public static List<String> faltantes(List<Persona> compradores, String nip) {
    List<String> out = new ArrayList<>();
    if (compradores == null || compradores.isEmpty()) {
      out.add("Compradores (lista de comparecientes o minuta)");
    }
    if (nip == null || nip.isBlank()) {
      out.add("NIP");
    }
    return out;
  }

  private CartaFinanciamiento() {}

  public static Map<String, Object> datos(
      List<Persona> vendedores,
      List<Persona> compradores,
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
  static String identificacion(List<Persona> personas) {
    List<String> partes = new ArrayList<>();
    for (Persona c : personas) {
      StringBuilder sb = new StringBuilder(c.nombre());
      if (!c.identificacion().isBlank()) {
        sb.append(c.personaJuridica() ? " con RUC No. " : " con cédula de ciudadanía No. ")
            .append(c.identificacion());
      }
      partes.add(sb.toString());
    }
    return unir(partes);
  }

  /** Un bloque por persona: línea de firma, parte, nombre y cédula. */
  static String firmas(List<Persona> personas, String etiqueta) {
    List<String> bloques = new ArrayList<>();
    for (Persona c : personas) {
      bloques.add(
          LINEA_FIRMA
              + "\n"
              + etiqueta
              + "\n"
              + c.nombre()
              + "\n"
              + (c.personaJuridica() ? "RUC: " : "C.I.: ")
              + c.identificacion());
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

  private static void marcas(Map<String, Object> out, String rubro, Pagador pagador) {
    out.put(rubro + "_vendedor", pagador == Pagador.VENDEDOR ? "X" : "");
    out.put(rubro + "_cliente", pagador == Pagador.CLIENTE ? "X" : "");
    out.put(rubro + "_financiado", pagador == Pagador.FINANCIADO ? "X" : "");
  }
}
