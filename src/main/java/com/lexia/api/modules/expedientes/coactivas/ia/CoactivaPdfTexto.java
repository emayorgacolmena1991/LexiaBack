package com.lexia.api.modules.expedientes.coactivas.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import com.lexia.api.modules.ia.ocr.DocumentoCacheStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
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

  public static final String VERSION_CACHE = "coactiva-ocr-v1";
  private static final String MARCA_FOJA = "\n--- FOJA ";

  private final AzureOcrService ocr;
  private final DocumentoCacheStore cache;

  public CoactivaPdfTexto(AzureOcrService ocr, DocumentoCacheStore cache) {
    this.ocr = ocr;
    this.cache = cache;
  }

  /**
   * Reanalizar el mismo PDF no vuelve a pagar Azure: el texto se guarda por tenant + sha256 del
   * archivo. Los bytes solo se leen del disco si no hay caché.
   */
  public TextoOcr extraer(UUID tenantId, String sha256, Supplier<byte[]> bytes, String mime, String nombre) {
    if (cache != null && StringUtils.hasText(sha256)) {
      var hit = cache.buscar(tenantId, sha256, VERSION_CACHE);
      if (hit.isPresent() && StringUtils.hasText(hit.get().textoOcr())) {
        String texto = asegurarUtf8(hit.get().textoOcr());
        LOG.info("OCR coactivas desde caché sha256={}", sha256);
        return new TextoOcr(texto, contarFojas(texto), texto.length());
      }
    }
    TextoOcr extraido = extraer(bytes.get(), mime, nombre);
    if (cache != null && StringUtils.hasText(sha256) && StringUtils.hasText(extraido.texto())
        && !calidadInsuficiente(extraido)) {
      cache.guardar(tenantId, sha256, VERSION_CACHE, "AZURE_READ", extraido.texto(), null);
    }
    return extraido;
  }

  public TextoOcr extraer(byte[] bytes, String mime, String nombre) {
    if (bytes == null || bytes.length == 0) {
      return TextoOcr.vacio();
    }
    if (!ocr.isConfigured()) {
      throw new IllegalStateException("Azure OCR no está configurado en este ambiente.");
    }
    String tipo = StringUtils.hasText(mime) ? mime : (esPdf(nombre, bytes) ? "application/pdf" : "application/octet-stream");
    try {
      TextoOcr extraido = formatear(ocr.analizarLectura(bytes, tipo));
      LOG.info("OCR Azure coactivas paginas={} chars={}", extraido.paginas(), extraido.caracteres());
      return extraido;
    } catch (RuntimeException e) {
      LOG.warn("OCR Azure falló: {}", e.getMessage());
      throw new IllegalStateException("El OCR de Azure falló: " + e.getMessage(), e);
    }
  }

  /** Hasta 12 extractos cortos. {@code q} de menos de 2 caracteres no busca. */
  public static List<Coincidencia> buscar(String texto, String q) {
    if (texto == null || texto.isEmpty() || q == null) {
      return List.of();
    }
    String needle = q.trim();
    if (needle.length() < 2) {
      return List.of();
    }
    if (needle.length() > 80) {
      needle = needle.substring(0, 80);
    }
    String hay = texto.toLowerCase(Locale.ROOT);
    String n = needle.toLowerCase(Locale.ROOT);
    List<Coincidencia> hits = new ArrayList<>();
    int from = 0;
    while (hits.size() < 12) {
      int at = hay.indexOf(n, from);
      if (at < 0) {
        break;
      }
      hits.add(new Coincidencia(fojaEn(texto, at), extracto(texto, at, n.length())));
      from = at + n.length();
    }
    return hits;
  }

  static int fojaEn(String texto, int pos) {
    int i = texto.lastIndexOf("--- FOJA ", pos);
    if (i < 0) {
      return 0;
    }
    int start = i + "--- FOJA ".length();
    int end = start;
    while (end < texto.length() && Character.isDigit(texto.charAt(end))) {
      end++;
    }
    if (end == start) {
      return 0;
    }
    try {
      return Integer.parseInt(texto.substring(start, end));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static String extracto(String texto, int at, int len) {
    int a = Math.max(0, at - 48);
    int b = Math.min(texto.length(), at + len + 48);
    String slice = texto.substring(a, b).replaceAll("--- FOJA \\d+ ---", " ");
    slice = slice.replaceAll("\\s+", " ").trim();
    return (a > 0 ? "..." : "") + slice + (b < texto.length() ? "..." : "");
  }

  public record Coincidencia(int foja, String extracto) {}

  static int contarFojas(String texto) {
    String t = "\n" + texto;
    int fojas = 0;
    for (int i = t.indexOf(MARCA_FOJA); i >= 0; i = t.indexOf(MARCA_FOJA, i + 1)) {
      fojas++;
    }
    return fojas;
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
      String utf8 = asegurarUtf8(content);
      return new TextoOcr(utf8, 0, utf8.length());
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
    String texto = asegurarUtf8(sb.toString().trim());
    if (texto.isEmpty()) {
      texto = asegurarUtf8(content);
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

  /**
   * UTF-8 leído como Latin-1 sale como {@code Ã³}/{@code Ã±}. El round-trip solo se aplica si
   * recupera Unicode válido; un texto que ya está bien no se toca.
   */
  static String asegurarUtf8(String texto) {
    if (texto == null || texto.isEmpty()) {
      return texto == null ? "" : texto;
    }
    boolean mojibake =
        texto.indexOf('\u00C3') >= 0 || texto.indexOf('\u00C2') >= 0 || texto.contains("\u00EF\u00BF\u00BD");
    if (!mojibake) {
      return texto;
    }
    String repaired = new String(texto.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    if (repaired.indexOf('\uFFFD') >= 0 || repaired.equals(texto)) {
      return texto;
    }
    return repaired;
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
