package com.lexia.api.modules.ia.ocr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Quita ruido notarial del OCR antes del prompt. Idempotente.
 * ponytail: no parte el expediente por cláusulas; si el texto sigue siendo enorme, el tope es 40_000.
 */
@Service
public class OcrTextSanitizerService {

  static final int TOPE = 40_000;

  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
  private static final Pattern RUIDO =
      Pattern.compile(
          "(?i)^(p[aá]gina\\s+\\d+(\\s+de\\s+\\d+)?|notaria.*|notar[ií]a.*|repertorio\\s*[:.].*|folio\\s*[:.]\\s*\\d+|sello\\s+notarial.*|registro\\s+de\\s+la\\s+propiedad\\s*$)$");
  private static final Pattern CEDULA = Pattern.compile("\\b\\d{9,10}\\b");
  private static final Pattern FECHA =
      Pattern.compile("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b");
  private static final Pattern CLAVE = Pattern.compile("(?i)clave\\s+catastral[:\\s]+([A-Z0-9-]{6,})");

  public String limpiar(String texto) {
    if (texto == null || texto.isBlank()) {
      return texto == null ? "" : texto;
    }
    String sinControl = CONTROL.matcher(texto).replaceAll("");
    Set<String> cedulas = new LinkedHashSet<>();
    Set<String> fechas = new LinkedHashSet<>();
    Set<String> claves = new LinkedHashSet<>();
    Matcher cedula = CEDULA.matcher(sinControl);
    while (cedula.find()) {
      cedulas.add(cedula.group());
    }
    Matcher fecha = FECHA.matcher(sinControl);
    while (fecha.find()) {
      fechas.add(fecha.group());
    }
    Matcher clave = CLAVE.matcher(sinControl);
    while (clave.find()) {
      claves.add(clave.group(1));
    }
    List<String> lineas = new ArrayList<>();
    for (String cruda : sinControl.split("\\R")) {
      String linea = cruda.replaceAll("[ \\t]{2,}", " ").trim();
      if (linea.isEmpty() || RUIDO.matcher(linea).matches()) {
        continue;
      }
      lineas.add(linea);
    }
    StringBuilder out = new StringBuilder();
    if (!cedulas.isEmpty() || !fechas.isEmpty() || !claves.isEmpty()) {
      out.append("DATOS DETERMINISTAS\n");
      if (!cedulas.isEmpty()) {
        out.append("cedulas: ").append(String.join(", ", cedulas)).append('\n');
      }
      if (!fechas.isEmpty()) {
        out.append("fechas: ").append(String.join(", ", fechas)).append('\n');
      }
      if (!claves.isEmpty()) {
        out.append("claves: ").append(String.join(", ", claves)).append('\n');
      }
      out.append('\n');
    }
    out.append(String.join("\n", lineas));
    if (out.length() <= TOPE) {
      return out.toString().trim();
    }
    return out.substring(0, TOPE).trim();
  }
}
