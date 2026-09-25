package com.lexia.api.modules.ia.cotejo;

import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoComparacion;
import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoFuente;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Parte el texto consolidado de E04, agrupa campos equivalentes y los compara.
 * No llama a Azure ni a un LLM.
 */
final class CotejoMotor {

  static final String COINCIDE = "COINCIDE";
  static final String DIFERENCIA = "DIFERENCIA";
  static final String NO_ENCONTRADO = "NO_ENCONTRADO";

  /** Marcadores de sección, incluido FE {@code === DOCUMENTO: TIPO ===} y markdown {@code # Tipo}. */
  private static final Pattern MARCADOR =
      Pattern.compile(
          "^(?:\\[\\[DOC:(.+?)\\]\\]|---\\s*(.+?)\\s*---|===\\s*DOCUMENTO:\\s*(.+?)\\s*===|#\\s+(.+?)|\\[([^\\]]+)\\])\\s*$");
  private static final Pattern FECHA_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern FECHA_NUM = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})");
  private static final Pattern FECHA_TEXTO =
      Pattern.compile("(\\d{1,2})\\s+(?:de\\s+)?([a-z]+)\\s+(?:de\\s+)?(\\d{4})");

  private static final List<String> ORDEN =
      List.of(
          "nombres",
          "identificacion",
          "fecha",
          "vigencia",
          "montos",
          "direccion",
          "inmueble",
          "linderos",
          "propietarios",
          "compradores",
          "avaluo");

  private static final Map<String, String> SINONIMOS = sinonimos();
  private static final Map<String, String> ETIQUETAS = etiquetas();
  private static final Map<String, String> DOCUMENTOS = documentos();
  private static final Map<String, Integer> MESES = meses();

  private CotejoMotor() {}

  record Bloque(String tipo, String texto) {}

  record DocCampos(String documento, Map<String, String> valores, Map<String, String> etiquetas) {}

  static List<Bloque> parse(String content) {
    if (!StringUtils.hasText(content)) {
      return List.of();
    }
    List<Bloque> nativos = parseCache(content);
    if (!nativos.isEmpty()) {
      return nativos;
    }
    return parseMarcadores(content);
  }

  static DocCampos desdeExtraccion(String tipo, DatosExtraidosDTO datos) {
    Map<String, String> valores = new LinkedHashMap<>();
    Map<String, String> etiquetas = new LinkedHashMap<>();
    if (datos != null && datos.datosClave() != null) {
      Map<String, String> planos = new LinkedHashMap<>();
      aplanar("", datos.datosClave(), planos);
      for (Map.Entry<String, String> entry : planos.entrySet()) {
        String canon = canon(entry.getKey());
        if (!StringUtils.hasText(canon) || !StringUtils.hasText(entry.getValue())) {
          continue;
        }
        valores.putIfAbsent(canon, entry.getValue().trim());
        etiquetas.putIfAbsent(canon, etiqueta(canon, entry.getKey()));
      }
    }
    return new DocCampos(etiquetaDocumento(tipo), valores, etiquetas);
  }

  static List<CotejoComparacion> comparar(List<DocCampos> docs) {
    if (docs == null || docs.size() < 2) {
      return List.of();
    }
    String relacion =
        String.join(" ↔ ", docs.stream().map(DocCampos::documento).toList());
    Map<String, String> etiquetas = new LinkedHashMap<>();
    for (DocCampos doc : docs) {
      etiquetas.putAll(doc.etiquetas());
    }

    List<String> campos = new ArrayList<>(etiquetas.keySet());
    campos.sort(
        (a, b) -> {
          int ia = ORDEN.indexOf(a);
          int ib = ORDEN.indexOf(b);
          if (ia < 0 && ib < 0) {
            return etiquetas.getOrDefault(a, a).compareToIgnoreCase(etiquetas.getOrDefault(b, b));
          }
          if (ia < 0) return 1;
          if (ib < 0) return -1;
          return Integer.compare(ia, ib);
        });

    List<CotejoComparacion> filas = new ArrayList<>();
    for (String campo : campos) {
      List<String> presentes = new ArrayList<>();
      List<CotejoFuente> fuentes = new ArrayList<>();
      for (DocCampos doc : docs) {
        String valor = doc.valores().get(campo);
        fuentes.add(new CotejoFuente(doc.documento(), valor));
        if (StringUtils.hasText(valor)) {
          presentes.add(valor);
        }
      }
      if (presentes.isEmpty()) {
        continue;
      }
      if (presentes.size() == 1 && !conceptoConocido(campo)) {
        continue;
      }
      String estado = estadoDe(campo, presentes, docs.size());
      String valorMostrado = COINCIDE.equals(estado) ? presentes.get(0) : null;
      if (NO_ENCONTRADO.equals(estado)) {
        valorMostrado = presentes.get(0);
      }
      filas.add(
          new CotejoComparacion(
              campo,
              etiquetas.getOrDefault(campo, humanize(campo)),
              estado,
              relacion,
              valorMostrado,
              List.copyOf(fuentes)));
    }
    return List.copyOf(filas);
  }

  static String fold(String raw) {
    String n = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFD);
    n = n.replaceAll("\\p{M}", "");
    return n.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
  }

  static String comparable(String canon, String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    return switch (modo(canon)) {
      case ID -> {
        String id = raw.replaceAll("\\D", "");
        yield id.isEmpty() ? fold(raw) : id;
      }
      case MONTO -> {
        String monto = montoComparable(raw);
        yield monto != null ? monto : fold(raw);
      }
      case FECHA -> {
        String fecha = fechaComparable(raw);
        yield fecha != null ? fecha : fold(raw);
      }
      case TEXTO -> fold(raw);
    };
  }

  private static String estadoDe(String campo, List<String> presentes, int totalDocs) {
    String base = comparable(campo, presentes.get(0));
    boolean iguales = true;
    for (int i = 1; i < presentes.size(); i++) {
      if (!java.util.Objects.equals(base, comparable(campo, presentes.get(i)))) {
        iguales = false;
        break;
      }
    }
    if (presentes.size() < totalDocs) {
      return iguales ? NO_ENCONTRADO : DIFERENCIA;
    }
    return iguales ? COINCIDE : DIFERENCIA;
  }

  private static boolean conceptoConocido(String canon) {
    return ORDEN.contains(canon) || canon.contains("fecha") || canon.contains("lindero");
  }

  private enum Modo {
    TEXTO,
    ID,
    MONTO,
    FECHA
  }

  private static Modo modo(String canon) {
    if ("identificacion".equals(canon) || canon.contains("cedula")) {
      return Modo.ID;
    }
    if ("avaluo".equals(canon)
        || "montos".equals(canon)
        || canon.contains("monto")
        || canon.contains("precio")
        || canon.contains("cuantia")) {
      return Modo.MONTO;
    }
    if ("fecha".equals(canon)
        || "vigencia".equals(canon)
        || canon.contains("fecha")
        || canon.contains("vigencia")) {
      return Modo.FECHA;
    }
    return Modo.TEXTO;
  }

  private static List<Bloque> parseCache(String content) {
    String[] lines = content.split("\\R", -1);
    List<Bloque> bloques = new ArrayList<>();
    int i = 0;
    while (i < lines.length) {
      if (!headerAt(lines, i)) {
        i++;
        continue;
      }
      String tipo = lines[i].substring(0, lines[i].length() - 1).trim();
      i += 2;
      StringBuilder cuerpo = new StringBuilder();
      boolean primera = true;
      while (i < lines.length && !headerAt(lines, i)) {
        String linea = lines[i];
        if (primera) {
          if (linea.startsWith(": ")) {
            linea = linea.substring(2);
          } else if (linea.startsWith(":")) {
            linea = linea.substring(1).trim();
          }
          primera = false;
        }
        if (cuerpo.length() > 0) {
          cuerpo.append('\n');
        }
        cuerpo.append(linea);
        i++;
      }
      String texto = cuerpo.toString().trim();
      if (StringUtils.hasText(tipo) && StringUtils.hasText(texto)) {
        bloques.add(new Bloque(tipo, texto));
      }
    }
    return bloques;
  }

  private static boolean headerAt(String[] lines, int i) {
    if (i + 2 >= lines.length) {
      return false;
    }
    String titulo = lines[i];
    if (titulo.length() < 2 || titulo.charAt(titulo.length() - 1) != ':') {
      return false;
    }
    if (titulo.indexOf(':') != titulo.length() - 1) {
      return false;
    }
    return ":".equals(lines[i + 1].trim()) && lines[i + 2].startsWith(":");
  }

  private static List<Bloque> parseMarcadores(String content) {
    String[] lines = content.split("\\R", -1);
    List<Bloque> bloques = new ArrayList<>();
    String tipo = null;
    StringBuilder cuerpo = new StringBuilder();
    for (String line : lines) {
      Matcher matcher = MARCADOR.matcher(line.trim());
      if (matcher.matches()) {
        cerrar(bloques, tipo, cuerpo);
        tipo = primerGrupo(matcher);
        cuerpo = new StringBuilder();
        continue;
      }
      if (tipo != null) {
        if (cuerpo.length() > 0) {
          cuerpo.append('\n');
        }
        cuerpo.append(line);
      }
    }
    cerrar(bloques, tipo, cuerpo);
    return bloques;
  }

  private static void cerrar(List<Bloque> bloques, String tipo, StringBuilder cuerpo) {
    if (!StringUtils.hasText(tipo) || cuerpo == null) {
      return;
    }
    String texto = cuerpo.toString().trim();
    if (StringUtils.hasText(texto)) {
      bloques.add(new Bloque(tipo.trim(), texto));
    }
  }

  private static String primerGrupo(Matcher matcher) {
    for (int i = 1; i <= matcher.groupCount(); i++) {
      if (matcher.group(i) != null) {
        return matcher.group(i).trim();
      }
    }
    return "";
  }

  private static void aplanar(String prefijo, Object valor, Map<String, String> out) {
    if (valor == null) {
      return;
    }
    if (valor instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String clave = String.valueOf(entry.getKey());
        String siguiente = prefijo.isEmpty() ? clave : prefijo + " " + clave;
        aplanar(siguiente, entry.getValue(), out);
      }
      return;
    }
    if (valor instanceof Iterable<?> iterable && !(valor instanceof String)) {
      List<String> partes = new ArrayList<>();
      for (Object item : iterable) {
        if (item != null && StringUtils.hasText(String.valueOf(item))) {
          partes.add(String.valueOf(item).trim());
        }
      }
      if (!partes.isEmpty() && StringUtils.hasText(prefijo)) {
        out.putIfAbsent(prefijo, String.join("; ", partes));
      }
      return;
    }
    if (!StringUtils.hasText(prefijo)) {
      return;
    }
    out.putIfAbsent(prefijo, textoPlano(valor));
  }

  private static String textoPlano(Object valor) {
    if (valor instanceof Number number) {
      return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
    }
    return String.valueOf(valor).trim();
  }

  private static String canon(String rawKey) {
    String clave = fold(rawKey).replaceAll("[^a-z0-9]", "");
    if (!StringUtils.hasText(clave)) {
      return "";
    }
    if (clave.contains("lindero")) {
      return "linderos";
    }
    if (clave.contains("avaluo")) {
      return "avaluo";
    }
    return SINONIMOS.getOrDefault(clave, clave);
  }

  private static String etiqueta(String canon, String rawKey) {
    return ETIQUETAS.getOrDefault(canon, humanize(rawKey));
  }

  static String etiquetaDocumento(String tipo) {
    if (!StringUtils.hasText(tipo)) {
      return "Documento";
    }
    String known = DOCUMENTOS.get(fold(tipo).replaceAll("[^a-z0-9]", ""));
    if (known != null) {
      return known;
    }
    String limpio = tipo.trim();
    if (!limpio.equals(limpio.toUpperCase(Locale.ROOT))) {
      return limpio;
    }
    return humanize(limpio);
  }

  private static String humanize(String raw) {
    String s = raw.replace('_', ' ').replace('-', ' ');
    s = s.replaceAll("([a-z])([A-Z])", "$1 $2").trim().replaceAll("\\s+", " ");
    if (s.isEmpty()) {
      return raw;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static String montoComparable(String raw) {
    String s = fold(raw).replace("us$", "").replace("usd", "").replace("$", "").replace("€", "");
    s = s.replace(" ", "");
    if (s.isEmpty()) {
      return null;
    }
    int coma = s.lastIndexOf(',');
    int punto = s.lastIndexOf('.');
    try {
      if (coma >= 0 && punto >= 0) {
        s = coma > punto ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
      } else if (coma >= 0) {
        s = s.matches("\\d{1,3}(,\\d{3})+") ? s.replace(",", "") : s.replace(',', '.');
      } else if (punto >= 0 && s.matches("\\d{1,3}(\\.\\d{3})+")) {
        s = s.replace(".", "");
      }
      return new BigDecimal(s).stripTrailingZeros().toPlainString();
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String fechaComparable(String raw) {
    String s =
        fold(raw)
            .replace(".", "")
            .replaceAll("^(vigente hasta|hasta el|hasta|fecha de|fecha)\\s+", "");
    Matcher iso = FECHA_ISO.matcher(s);
    if (iso.find()) {
      return iso.group(1) + "-" + iso.group(2) + "-" + iso.group(3);
    }
    Matcher num = FECHA_NUM.matcher(s);
    if (num.find()) {
      return String.format(
          Locale.ROOT,
          "%s-%02d-%02d",
          num.group(3),
          Integer.parseInt(num.group(2)),
          Integer.parseInt(num.group(1)));
    }
    Matcher texto = FECHA_TEXTO.matcher(s);
    if (texto.find()) {
      Integer mes = MESES.get(texto.group(2));
      if (mes != null) {
        return String.format(
            Locale.ROOT,
            "%s-%02d-%02d",
            texto.group(3),
            mes,
            Integer.parseInt(texto.group(1)));
      }
    }
    return null;
  }

  private static Map<String, String> sinonimos() {
    Map<String, String> map = new LinkedHashMap<>();
    alias(map, "nombres", "nombre", "nombres", "apellido", "apellidos", "nombrecompleto",
        "nombresyapellidos", "nombresapellidos", "nombreyapellidos", "nombreyapellido");
    alias(map, "identificacion", "cedula", "ceduladeidentidad", "cedulaciudadania",
        "numerodecedula", "numerocedula", "nrocedula", "identificacion",
        "numerodeidentificacion", "numeroidentificacion", "documentoidentidad", "dni");
    alias(map, "direccion", "direccion", "domicilio", "direccioninmueble", "ubicacion");
    alias(map, "inmueble", "inmueble", "datosdelinmueble", "descripcioninmueble",
        "descripciondelinmueble");
    alias(map, "propietarios", "propietario", "propietarios", "dueno", "duenos");
    alias(map, "compradores", "comprador", "compradores", "adquirente", "adquirentes");
    alias(map, "montos", "monto", "montos", "valor", "precio", "cuantia", "precioventa",
        "valorventa");
    alias(map, "vigencia", "vigencia", "vigenciadocumental", "vigentehasta", "fechavigencia");
    alias(map, "fecha", "fecha", "fechas");
    return Map.copyOf(map);
  }

  private static void alias(Map<String, String> map, String canon, String... claves) {
    for (String clave : claves) {
      map.put(clave, canon);
    }
  }

  private static Map<String, String> etiquetas() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("nombres", "Nombres y apellidos");
    map.put("identificacion", "Número de identificación");
    map.put("direccion", "Dirección");
    map.put("linderos", "Linderos del inmueble");
    map.put("avaluo", "Avalúo");
    map.put("inmueble", "Datos del inmueble");
    map.put("propietarios", "Propietarios");
    map.put("compradores", "Compradores");
    map.put("montos", "Monto");
    map.put("vigencia", "Vigencia documental");
    map.put("fecha", "Fecha");
    return Map.copyOf(map);
  }

  private static Map<String, String> documentos() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("cedula", "Cédula");
    map.put("ceduladeidentidad", "Cédula");
    map.put("cedulaciudadania", "Cédula");
    map.put("minuta", "Minuta");
    map.put("escritura", "Escritura");
    map.put("escriturapublica", "Escritura");
    map.put("avaluo", "Avalúo");
    map.put("avaluocomercial", "Avalúo");
    map.put("historiadominio", "Historia de dominio");
    map.put("certificadodetradicion", "Historia de dominio");
    return Map.copyOf(map);
  }

  private static Map<String, Integer> meses() {
    Map<String, Integer> map = new LinkedHashMap<>();
    String[] nombres = {
      "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
      "octubre", "noviembre", "diciembre"
    };
    for (int i = 0; i < nombres.length; i++) {
      map.put(nombres[i], i + 1);
    }
    map.put("setiembre", 9);
    map.put("ene", 1);
    map.put("feb", 2);
    map.put("mar", 3);
    map.put("abr", 4);
    map.put("may", 5);
    map.put("jun", 6);
    map.put("jul", 7);
    map.put("ago", 8);
    map.put("sep", 9);
    map.put("sept", 9);
    map.put("oct", 10);
    map.put("nov", 11);
    map.put("dic", 12);
    return Map.copyOf(map);
  }
}
