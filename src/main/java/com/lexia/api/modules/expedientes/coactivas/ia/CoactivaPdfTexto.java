package com.lexia.api.modules.expedientes.coactivas.ia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  private static final ObjectMapper JSON = new ObjectMapper();
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
      // ponytail: la caché vieja guardó solo texto. Sin polígonos, el siguiente análisis vuelve a Azure una vez.
      if (hit.isPresent()
          && StringUtils.hasText(hit.get().textoOcr())
          && StringUtils.hasText(hit.get().resultadoJson())) {
        String texto = asegurarUtf8(hit.get().textoOcr());
        LOG.info("OCR coactivas desde caché sha256={}", sha256);
        return new TextoOcr(texto, contarFojas(texto), texto.length(), hit.get().resultadoJson());
      }
    }
    TextoOcr extraido = extraer(bytes.get(), mime, nombre);
    if (cache != null && StringUtils.hasText(sha256) && StringUtils.hasText(extraido.texto())
        && !calidadInsuficiente(extraido)) {
      cache.guardar(tenantId, sha256, VERSION_CACHE, "AZURE_READ", extraido.texto(), extraido.indice());
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
      JsonNode analyze = ocr.analizarLectura(bytes, tipo);
      TextoOcr base = formatear(analyze);
      TextoOcr extraido = new TextoOcr(base.texto(), base.paginas(), base.caracteres(), indicePalabras(analyze));
      LOG.info("OCR Azure coactivas paginas={} chars={}", extraido.paginas(), extraido.caracteres());
      return extraido;
    } catch (RuntimeException e) {
      LOG.warn("OCR Azure falló: {}", e.getMessage());
      throw new IllegalStateException("El OCR de Azure falló: " + e.getMessage(), e);
    }
  }

  /** Hasta 12 extractos cortos. {@code q} de menos de 2 caracteres no busca. */
  public static List<Coincidencia> buscar(String texto, String q) {
    return buscar(texto, null, q);
  }

  /**
   * Si {@code indiceJson} trae las palabras de Azure, cada acierto incluye el polígono.
   * Sin índice (caché vieja) el resultado es solo foja y extracto.
   */
  public static List<Coincidencia> buscar(String texto, String indiceJson, String q) {
    String needle = aguja(q);
    if (needle == null) {
      return List.of();
    }
    JsonNode indice = leerIndice(indiceJson);
    if (indice != null) {
      List<Coincidencia> conCajas = buscarEnIndice(indice, needle);
      if (!conCajas.isEmpty()) {
        return conCajas;
      }
    }
    if (texto == null || texto.isEmpty()) {
      return List.of();
    }
    String hay = texto.toLowerCase(Locale.ROOT);
    List<Coincidencia> hits = new ArrayList<>();
    int from = 0;
    while (hits.size() < 12) {
      int at = hay.indexOf(needle, from);
      if (at < 0) {
        break;
      }
      hits.add(new Coincidencia(fojaEn(texto, at), extracto(texto, at, needle.length())));
      from = at + needle.length();
    }
    return hits;
  }

  /**
   * Índice compacto de {@code analyzeResult} ({@code prebuilt-read}): una entrada por palabra,
   * o por línea si la página no trae palabras con polígono. Coordenadas en la unidad de la página
   * (pulgadas o píxeles), origen arriba a la izquierda.
   */
  static String indicePalabras(JsonNode analyzeResult) {
    ObjectNode root = JSON.createObjectNode();
    ArrayNode pages = root.putArray("pages");
    JsonNode fuente = analyzeResult == null ? null : analyzeResult.path("pages");
    if (fuente != null && fuente.isArray()) {
      int index = 0;
      for (JsonNode page : fuente) {
        index++;
        double width = page.path("width").asDouble(0);
        double height = page.path("height").asDouble(0);
        if (width <= 0 || height <= 0) {
          continue;
        }
        JsonNode words = palabrasDe(page);
        if (!words.isArray() || words.isEmpty()) {
          continue;
        }
        ObjectNode out = pages.addObject();
        out.put("n", page.path("pageNumber").asInt(index));
        out.put("w", width);
        out.put("h", height);
        out.put("u", page.path("unit").asText("inch"));
        ArrayNode lista = out.putArray("words");
        for (JsonNode word : words) {
          String t = word.path("content").asText("").replaceAll("\\s+", " ").trim();
          List<Double> poly = leerPoligono(word);
          if (t.isEmpty() || poly.isEmpty()) {
            continue;
          }
          ObjectNode item = lista.addObject();
          item.put("t", t);
          ArrayNode coords = item.putArray("p");
          for (Double n : poly) {
            coords.add(n);
          }
        }
        if (lista.isEmpty()) {
          pages.remove(pages.size() - 1);
        }
      }
    }
    try {
      return JSON.writeValueAsString(root);
    } catch (JsonProcessingException ex) {
      return null;
    }
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

  public record Caja(int pagina, double pageWidth, double pageHeight, String unit, List<Double> polygon) {}

  public record Coincidencia(int foja, String extracto, List<Caja> cajas) {
    public Coincidencia {
      cajas = cajas == null ? List.of() : List.copyOf(cajas);
    }

    public Coincidencia(int foja, String extracto) {
      this(foja, extracto, List.of());
    }
  }

  private static String aguja(String q) {
    if (q == null) {
      return null;
    }
    String needle = q.trim();
    if (needle.length() < 2) {
      return null;
    }
    if (needle.length() > 80) {
      needle = needle.substring(0, 80);
    }
    return needle.toLowerCase(Locale.ROOT);
  }

  private static JsonNode leerIndice(String indiceJson) {
    if (!StringUtils.hasText(indiceJson)) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(indiceJson);
      return node.path("pages").isArray() ? node : null;
    } catch (JsonProcessingException ex) {
      return null;
    }
  }

  private static List<Coincidencia> buscarEnIndice(JsonNode indice, String needle) {
    List<Coincidencia> hits = new ArrayList<>();
    for (JsonNode page : indice.path("pages")) {
      JsonNode words = page.path("words");
      if (!words.isArray() || words.isEmpty()) {
        continue;
      }
      StringBuilder sb = new StringBuilder();
      int[] inicio = new int[words.size()];
      int[] fin = new int[words.size()];
      int usados = 0;
      for (JsonNode word : words) {
        String t = word.path("t").asText("");
        if (t.isEmpty()) {
          continue;
        }
        if (usados > 0) {
          sb.append(' ');
        }
        inicio[usados] = sb.length();
        sb.append(t);
        fin[usados] = sb.length();
        usados++;
      }
      if (usados == 0) {
        continue;
      }
      String hay = sb.toString().toLowerCase(Locale.ROOT);
      int from = 0;
      while (hits.size() < 12) {
        int at = hay.indexOf(needle, from);
        if (at < 0) {
          break;
        }
        int matchEnd = at + needle.length();
        List<Caja> cajas = new ArrayList<>();
        int cursor = 0;
        for (JsonNode word : words) {
          if (word.path("t").asText("").isEmpty()) {
            continue;
          }
          if (cursor < usados && fin[cursor] > at && inicio[cursor] < matchEnd) {
            cajas.add(cajaDe(page, word));
          }
          cursor++;
        }
        hits.add(new Coincidencia(page.path("n").asInt(), extracto(sb.toString(), at, needle.length()), cajas));
        from = matchEnd;
      }
      if (hits.size() >= 12) {
        break;
      }
    }
    return hits;
  }

  private static Caja cajaDe(JsonNode page, JsonNode word) {
    return new Caja(
        page.path("n").asInt(),
        page.path("w").asDouble(),
        page.path("h").asDouble(),
        page.path("u").asText("inch"),
        leerPoligono(word));
  }

  /** Palabras con polígono; si no hay, las líneas. */
  private static JsonNode palabrasDe(JsonNode page) {
    JsonNode words = page.path("words");
    if (words.isArray()) {
      for (JsonNode word : words) {
        if (!word.path("content").asText("").isBlank() && !leerPoligono(word).isEmpty()) {
          return words;
        }
      }
    }
    JsonNode lines = page.path("lines");
    return lines.isArray() ? lines : JSON.createArrayNode();
  }

  /** {@code polygon} o {@code boundingBox}: lista plana o puntos {@code {x,y}}. En el índice la clave es {@code p}. */
  static List<Double> leerPoligono(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return List.of();
    }
    JsonNode poly = node.has("polygon") ? node.get("polygon") : node.has("boundingBox") ? node.get("boundingBox") : node.get("p");
    if (poly == null || !poly.isArray() || poly.size() < 2) {
      return List.of();
    }
    List<Double> out = new ArrayList<>();
    if (poly.get(0).isNumber()) {
      for (JsonNode n : poly) {
        if (n.isNumber()) {
          out.add(n.asDouble());
        }
      }
    } else {
      for (JsonNode pt : poly) {
        out.add(pt.path("x").asDouble());
        out.add(pt.path("y").asDouble());
      }
    }
    if ((out.size() & 1) == 1) {
      out.remove(out.size() - 1);
    }
    return out.size() >= 4 ? out : List.of();
  }

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

  public record TextoOcr(String texto, int paginas, int caracteres, String indice) {
    public TextoOcr(String texto, int paginas, int caracteres) {
      this(texto, paginas, caracteres, null);
    }

    public static TextoOcr vacio() {
      return new TextoOcr("", 0, 0, null);
    }
  }
}
