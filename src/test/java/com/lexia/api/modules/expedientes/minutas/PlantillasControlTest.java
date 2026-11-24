package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Control de las plantillas DOCX (escrituración y notaría): tags enteros, sin textos de borrador,
 * sin comentarios de Word y con las cláusulas numeradas sin repetirse dentro de cada parte. Mismas
 * reglas que {@code tools/plantillas/plantillas.py revisar}.
 */
class PlantillasControlTest {

  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final Pattern TAG = Pattern.compile("\\{\\{([^{}]*)\\}\\}");
  private static final Pattern BORRADOR = Pattern.compile("(?i)X{4,}|XX\\.000,00");
  private static final Pattern PARTE =
      Pattern.compile("^\\s*(PRIMERA|SEGUNDA|TERCERA)\\s+PARTE\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CLAUSULA =
      Pattern.compile("CL[AÁ]USULA\\s+([A-ZÁÉÍÓÚÜÑ ]+?)\\s*(?=[:.\\-–—]|$)", Pattern.CASE_INSENSITIVE);
  private static final Map<String, Integer> ORDINALES = new LinkedHashMap<>();

  static {
    String[] unidades = {"PRIMER", "SEGUND", "TERCER", "CUART", "QUINT", "SEXT", "SEPTIM", "OCTAV", "NOVEN"};
    for (int i = 0; i < unidades.length; i++) {
      ORDINALES.put(unidades[i] + "A", i + 1);
      ORDINALES.put(unidades[i] + "O", i + 1);
    }
    ORDINALES.put("DECIMA", 10);
    ORDINALES.put("DECIMO", 10);
    ORDINALES.put("VIGESIMA", 20);
    ORDINALES.put("VIGESIMO", 20);
    ORDINALES.put("UNDECIMA", 11);
    ORDINALES.put("DUODECIMA", 12);
  }

  private final MinutaTemplateCatalog catalog = new MinutaTemplateCatalog();
  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();

  @Test
  void plantillasSinTagsPartidosBorradorComentariosNiClausulasRepetidas() throws Exception {
    List<String> problemas = new ArrayList<>();
    for (MinutaTemplateDescriptor descriptor : catalog.all()) {
      String nombre = descriptor.classpathResource();
      Map<String, byte[]> partes = partes(leer(nombre));
      if (partes.containsKey("word/comments.xml")) {
        problemas.add(nombre + ": tiene comentarios de Word");
      }
      List<List<Integer>> clausulas = new ArrayList<>();
      for (Map.Entry<String, byte[]> parte : partes.entrySet()) {
        if (!esTexto(parte.getKey())) {
          continue;
        }
        Element root = dom(parte.getValue());
        if (root.getElementsByTagNameNS(W, "commentReference").getLength() > 0) {
          problemas.add(nombre + ": referencia a comentario en " + parte.getKey());
        }
        NodeList ps = root.getElementsByTagNameNS(W, "p");
        for (int i = 0; i < ps.getLength(); i++) {
          List<String> ts = textosPropios((Element) ps.item(i));
          String linea = String.join("", ts);
          for (String partido : tagsPartidos(ts)) {
            problemas.add(nombre + ": tag partido " + partido);
          }
          Matcher b = BORRADOR.matcher(linea);
          if (b.find()) {
            problemas.add(nombre + ": texto de borrador «" + b.group() + "»");
          }
          if ("word/document.xml".equals(parte.getKey())) {
            acumularClausula(linea, clausulas);
          }
        }
      }
      for (int p = 0; p < clausulas.size(); p++) {
        Set<Integer> vistos = new HashSet<>();
        for (Integer n : clausulas.get(p)) {
          if (n != null && !vistos.add(n)) {
            problemas.add(nombre + ": cláusula " + n + " repetida en la parte " + (p + 1));
          }
        }
      }
    }
    assertEquals(List.of(), problemas);
  }

  /** La de notaría usa sus propias variables (NotaryEscrituraService); las 10 del bufete, las de la minuta. */
  @Test
  void tagsDeEscrituracionExistenEnLaMinutaOEnSusAlias() throws Exception {
    Set<String> conocidos = new MinutaViviendaData().toTemplateMap().keySet();
    Set<String> archivos = new HashSet<>();
    for (MinutaTemplateDescriptor descriptor : escrituracion()) {
      assertEquals(
          List.of(),
          MinutaTagAliases.sinResolver(renderer.tags(descriptor), conocidos),
          descriptor.classpathResource());
      archivos.add(descriptor.classpathResource());
    }
    // Individual y Solidaria comparten archivos: 12 entradas del catálogo, 10 plantillas.
    assertEquals(10, archivos.size());
  }

  @Test
  void cadaPlantillaSeRenderizaCompletaYSinComentarios() throws Exception {
    MinutaViviendaData data = datosCompletos();
    for (MinutaTemplateDescriptor descriptor : escrituracion()) {
      byte[] docx = renderer.renderVivienda(descriptor, data);
      String texto;
      try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx));
          XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
        texto = extractor.getText();
      }
      String nombre = descriptor.classpathResource();
      assertFalse(texto.contains("{{"), nombre + ": quedaron tags");
      assertFalse(texto.contains("nodata"), nombre + ": quedaron datos sin resolver");
      assertFalse(partes(docx).containsKey("word/comments.xml"), nombre + ": la minuta trae comentarios");
    }
  }

  @Test
  void ordinalesEnTextoSeLeen() {
    assertEquals(8, ordinal("OCTAVA"));
    assertEquals(17, ordinal("DÉCIMA SÉPTIMA"));
    assertEquals(13, ordinal("DecimoTercera"));
    assertEquals(7, ordinal("SÉPTIMA INSPECCIÓN"));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private List<MinutaTemplateDescriptor> escrituracion() {
    return catalog.all().stream()
        .filter(d -> d.classpathResource().startsWith("templates/escrituracion/"))
        .toList();
  }

  private static MinutaViviendaData datosCompletos() {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : new MinutaViviendaData().toTemplateMap().keySet()) {
      values.put(key, "V_" + key);
    }
    return new ObjectMapper().convertValue(values, MinutaViviendaData.class);
  }

  private static void acumularClausula(String linea, List<List<Integer>> clausulas) {
    boolean abre = PARTE.matcher(linea).find();
    Matcher m = CLAUSULA.matcher(linea);
    boolean esClausula = abre ? m.find() : m.find() && m.start() == inicio(linea);
    if (abre || clausulas.isEmpty()) {
      clausulas.add(new ArrayList<>());
    }
    if (esClausula) {
      clausulas.get(clausulas.size() - 1).add(ordinal(m.group(1)));
    }
  }

  private static int inicio(String linea) {
    int i = 0;
    while (i < linea.length() && Character.isWhitespace(linea.charAt(i))) {
      i++;
    }
    return i;
  }

  /** Prefijo ordinal de «SÉPTIMA INSPECCIÓN» → 7; {@code null} si no empieza con un ordinal. */
  static Integer ordinal(String palabras) {
    String plano =
        Normalizer.normalize(palabras, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT);
    int total = 0;
    for (String palabra : plano.trim().split("\\s+")) {
      List<String> trozos =
          palabra.length() > 6 && (palabra.startsWith("DECIMO") || palabra.startsWith("DECIMA"))
              ? List.of(palabra.substring(0, 6), palabra.substring(6))
              : List.of(palabra);
      int suma = 0;
      for (String trozo : trozos) {
        Integer v = ORDINALES.get(trozo);
        if (v == null) {
          return total == 0 ? null : total;
        }
        suma += v;
      }
      total += suma;
    }
    return total == 0 ? null : total;
  }

  private static List<String> tagsPartidos(List<String> ts) {
    List<String> out = new ArrayList<>();
    int[] fines = new int[ts.size()];
    int pos = 0;
    for (int i = 0; i < ts.size(); i++) {
      pos += ts.get(i).length();
      fines[i] = pos;
    }
    Matcher m = TAG.matcher(String.join("", ts));
    while (m.find()) {
      if (indice(fines, m.start(), false) != indice(fines, m.end(), true)) {
        out.add(m.group());
      }
    }
    return out;
  }

  private static int indice(int[] fines, int posicion, boolean fin) {
    for (int i = 0; i < fines.length; i++) {
      if (fin ? fines[i] >= posicion : fines[i] > posicion) {
        return i;
      }
    }
    return fines.length - 1;
  }

  /** w:t del párrafo sin los de un cuadro de texto anidado (esos tienen su propio w:p). */
  private static List<String> textosPropios(Element p) {
    List<String> out = new ArrayList<>();
    NodeList ts = p.getElementsByTagNameNS(W, "t");
    for (int i = 0; i < ts.getLength(); i++) {
      Node t = ts.item(i);
      Node padre = t.getParentNode();
      while (padre != null && !(W.equals(padre.getNamespaceURI()) && "p".equals(padre.getLocalName()))) {
        padre = padre.getParentNode();
      }
      if (padre == p) {
        out.add(t.getTextContent());
      }
    }
    return out;
  }

  private static boolean esTexto(String nombre) {
    return "word/document.xml".equals(nombre) || nombre.matches("word/(header|footer)\\d*\\.xml");
  }

  private static Element dom(byte[] xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
  }

  private static Map<String, byte[]> partes(byte[] docx) throws IOException {
    Map<String, byte[]> out = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        out.put(e.getName(), zip.readAllBytes());
      }
    }
    return out;
  }

  private static byte[] leer(String classpathResource) throws IOException {
    try (InputStream in =
        PlantillasControlTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
      assertTrue(in != null, "No existe " + classpathResource);
      return in.readAllBytes();
    }
  }
}
