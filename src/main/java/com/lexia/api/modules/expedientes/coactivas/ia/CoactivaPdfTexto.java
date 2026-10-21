package com.lexia.api.modules.expedientes.coactivas.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OCR de coactivas: el PDF completo va a Azure Document Intelligence ({@code prebuilt-read}).
 * PDFBox no se usa: en un escaneo devuelve solo las marcas de foja y el pipeline creía que ya
 * había texto.
 */
@Component
public class CoactivaPdfTexto {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaPdfTexto.class);
  static final int MIN_CARACTERES = 50;
  static final int MIN_FOJAS_CALIDAD = 5;

  private final AzureOcrService ocr;

  public CoactivaPdfTexto(AzureOcrService ocr) {
    this.ocr = ocr;
  }

  public TextoOcr extraer(byte[] bytes, String mime, String nombre) {
    if (bytes == null || bytes.length == 0) {
      return TextoOcr.vacio();
    }
    if (!ocr.isConfigured()) {
      LOG.warn("Azure OCR no configurado");
      return TextoOcr.vacio();
    }
    String tipo = StringUtils.hasText(mime) ? mime : (esPdf(nombre, bytes) ? "application/pdf" : "application/octet-stream");
    try {
      TextoOcr extraido = formatear(ocr.analizarLectura(bytes, tipo));
      LOG.info("OCR Azure coactivas paginas={} chars={}", extraido.paginas(), extraido.caracteres());
      return extraido;
    } catch (RuntimeException e) {
      LOG.warn("OCR Azure falló: {}", e.getMessage());
      return TextoOcr.vacio();
    }
  }

  /** Más de 5 fojas y menos de 50 caracteres de texto: no vale la pena llamar al LLM. */
  public static boolean calidadInsuficiente(TextoOcr extraido) {
    return extraido != null
        && extraido.paginas() > MIN_FOJAS_CALIDAD
        && extraido.caracteres() < MIN_CARACTERES;
  }

  public static String mensajeCalidad(int paginas) {
    return "La digitalización no tiene texto legible ("
        + paginas
        + " fojas). Vuelva a escanear el expediente.";
  }

  static TextoOcr formatear(JsonNode analyzeResult) {
    if (analyzeResult == null || analyzeResult.isMissingNode() || analyzeResult.isNull()) {
      return TextoOcr.vacio();
    }
    String content = analyzeResult.path("content").asText("").trim();
    JsonNode pages = analyzeResult.path("pages");
    if (!pages.isArray() || pages.isEmpty()) {
      return new TextoOcr(content, 0, content.length());
    }
    StringBuilder sb = new StringBuilder();
    int charsLineas = 0;
    int index = 0;
    for (JsonNode page : pages) {
      index++;
      int num = page.path("pageNumber").asInt(index);
      sb.append("\n--- FOJA ").append(num).append(" ---\n");
      charsLineas += appendPagina(sb, page);
    }
    String texto = sb.toString().trim();
    if (texto.isEmpty()) {
      texto = content;
    }
    int caracteres = content.isEmpty() ? charsLineas : content.length();
    return new TextoOcr(texto, pages.size(), caracteres);
  }

  private static int appendPagina(StringBuilder sb, JsonNode page) {
    JsonNode lines = page.path("lines");
    if (lines.isArray() && !lines.isEmpty()) {
      int chars = 0;
      for (JsonNode line : lines) {
        String t = line.path("content").asText("").trim();
        if (t.isEmpty()) {
          continue;
        }
        sb.append(t).append('\n');
        chars += t.length();
      }
      return chars;
    }
    JsonNode words = page.path("words");
    if (!words.isArray() || words.isEmpty()) {
      return 0;
    }
    int chars = 0;
    boolean any = false;
    for (JsonNode word : words) {
      String t = word.path("content").asText("").trim();
      if (t.isEmpty()) {
        continue;
      }
      if (any) {
        sb.append(' ');
      }
      sb.append(t);
      chars += t.length();
      any = true;
    }
    if (any) {
      sb.append('\n');
    }
    return chars;
  }

  static boolean esPdf(String nombre, byte[] bytes) {
    String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
    if (n.endsWith(".pdf")) {
      return true;
    }
    return bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
  }

  public record TextoOcr(String texto, int paginas, int caracteres) {
    public static TextoOcr vacio() {
      return new TextoOcr("", 0, 0);
    }
  }
}
