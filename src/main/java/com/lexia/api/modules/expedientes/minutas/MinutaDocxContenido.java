package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ParrafoEditado;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ParrafoMinuta;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lectura y edición por párrafo del DOCX guardado de una minuta. El DOCX es la única fuente: el
 * editor muestra estos párrafos y la descarga devuelve el mismo archivo.
 *
 * <p>Los índices recorren el cuerpo en orden (párrafos y celdas de tablas, recursivo). poi-tl solo
 * sustituye texto dentro de los runs, así que el DOCX renderizado conserva la misma secuencia de
 * párrafos que su plantilla; eso permite ubicar los tags de la plantilla en el documento editado.
 */
@Component
public class MinutaDocxContenido {

  private static final Logger LOG = LoggerFactory.getLogger(MinutaDocxContenido.class);
  private static final Pattern TAG = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
  private static final int TITULO_MAX_CHARS = 160;

  public List<ParrafoMinuta> leer(byte[] docx) {
    try (XWPFDocument doc = abrir(docx)) {
      List<ParrafoMinuta> out = new ArrayList<>();
      List<Ubicado> parrafos = parrafos(doc);
      for (int i = 0; i < parrafos.size(); i++) {
        Ubicado u = parrafos.get(i);
        String texto = texto(u.p());
        boolean negrita = todoNegrita(u.p());
        String estilo = u.p().getStyle();
        out.add(
            new ParrafoMinuta(
                i,
                texto,
                estilo,
                alineacion(u.p()),
                negrita,
                esTitulo(estilo, texto, negrita),
                u.enTabla()));
      }
      return out;
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el DOCX de la minuta: " + e.getMessage());
    }
  }

  /** Aplica el texto nuevo de cada párrafo indicado. Devuelve el DOCX resultante. */
  public byte[] editar(byte[] docx, List<ParrafoEditado> cambios) {
    try (XWPFDocument doc = abrir(docx)) {
      List<Ubicado> parrafos = parrafos(doc);
      for (ParrafoEditado cambio : cambios == null ? List.<ParrafoEditado>of() : cambios) {
        if (cambio == null) {
          continue;
        }
        if (cambio.index() < 0 || cambio.index() >= parrafos.size()) {
          throw ApiException.badRequest("Párrafo fuera de rango: " + cambio.index());
        }
        setTexto(parrafos.get(cambio.index()).p(), cambio.texto() == null ? "" : cambio.texto());
      }
      return escribir(doc);
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo editar el DOCX de la minuta: " + e.getMessage());
    }
  }

  /**
   * Sustituye en un DOCX ya editado solo los valores de {@code tags} que cambiaron ({@code antes} →
   * {@code despues}, valores de {@link MinutaViviendaData#toTemplateMap()}). Cada ocurrencia se
   * ubica por el párrafo de la plantilla que contiene el tag; si el usuario ya reescribió ese valor
   * a mano, se respeta su texto.
   */
  public byte[] actualizarValores(
      byte[] docxEditado,
      byte[] plantilla,
      Map<String, Object> antes,
      Map<String, Object> despues,
      Collection<String> tags) {
    List<String> cambiados =
        tags.stream().filter(t -> !valor(antes, t).equals(valor(despues, t))).toList();
    if (cambiados.isEmpty()) {
      return docxEditado;
    }
    try (XWPFDocument doc = abrir(docxEditado);
        XWPFDocument tpl = abrir(plantilla)) {
      List<Ubicado> editados = parrafos(doc);
      List<Ubicado> origen = parrafos(tpl);
      if (editados.size() != origen.size()) {
        LOG.warn(
            "DOCX editado ({} párrafos) no calza con la plantilla ({}); reemplazo por valor",
            editados.size(),
            origen.size());
        reemplazarPorValor(editados, cambiados, antes, despues);
        return escribir(doc);
      }
      for (int i = 0; i < origen.size(); i++) {
        reemplazarEnParrafo(texto(origen.get(i).p()), editados.get(i).p(), cambiados, antes, despues);
      }
      return escribir(doc);
    } catch (IOException e) {
      throw ApiException.badRequest(
          "No se pudieron actualizar los datos en el DOCX editado: " + e.getMessage());
    }
  }

  private static void reemplazarEnParrafo(
      String textoPlantilla,
      XWPFParagraph editado,
      List<String> cambiados,
      Map<String, Object> antes,
      Map<String, Object> despues) {
    record Ocurrencia(int ordinal, String anterior, String nuevo) {}
    List<Ocurrencia> ocurrencias = new ArrayList<>();
    Matcher m = TAG.matcher(textoPlantilla);
    while (m.find()) {
      String tag = m.group(1);
      if (!cambiados.contains(tag)) {
        continue;
      }
      String anterior = valor(antes, tag);
      String previo = renderizar(textoPlantilla.substring(0, m.start()), antes);
      ocurrencias.add(new Ocurrencia(contar(previo, anterior), anterior, valor(despues, tag)));
    }
    // De atrás hacia adelante: cada reemplazo no desplaza a los anteriores.
    for (int j = ocurrencias.size() - 1; j >= 0; j--) {
      Ocurrencia o = ocurrencias.get(j);
      String actual = texto(editado);
      int pos = enesima(actual, o.anterior(), o.ordinal());
      if (pos < 0) {
        continue;
      }
      setTexto(
          editado,
          actual.substring(0, pos) + o.nuevo() + actual.substring(pos + o.anterior().length()));
    }
  }

  private static void reemplazarPorValor(
      List<Ubicado> parrafos,
      List<String> cambiados,
      Map<String, Object> antes,
      Map<String, Object> despues) {
    for (String tag : cambiados) {
      String anterior = valor(antes, tag);
      if (MinutaViviendaData.isMissing(anterior)) {
        // "nodata" se repite en muchos campos: sin la plantilla no se puede ubicar con certeza.
        continue;
      }
      for (Ubicado u : parrafos) {
        String actual = texto(u.p());
        if (actual.contains(anterior)) {
          setTexto(u.p(), actual.replace(anterior, valor(despues, tag)));
        }
      }
    }
  }

  /**
   * Cambia el texto del párrafo tocando solo el tramo que difiere (prefijo/sufijo comunes intactos),
   * así se conserva el formato de los runs no editados.
   */
  static void setTexto(XWPFParagraph p, String nuevo) {
    String actual = texto(p);
    if (actual.equals(nuevo)) {
      return;
    }
    List<XWPFRun> runs = p.getRuns();
    if (runs.isEmpty()) {
      escribirRun(p.createRun(), nuevo);
      return;
    }
    int max = Math.min(actual.length(), nuevo.length());
    int pre = 0;
    while (pre < max && actual.charAt(pre) == nuevo.charAt(pre)) {
      pre++;
    }
    int suf = 0;
    while (suf < actual.length() - pre
        && suf < nuevo.length() - pre
        && actual.charAt(actual.length() - 1 - suf) == nuevo.charAt(nuevo.length() - 1 - suf)) {
      suf++;
    }
    int finAnterior = actual.length() - suf;
    String insertado = nuevo.substring(pre, nuevo.length() - suf);

    int start = 0;
    boolean insertadoUsado = false;
    for (XWPFRun run : runs) {
      String t = textoRun(run);
      int end = start + t.length();
      // El texto nuevo hereda el formato del run donde empieza el cambio.
      boolean destino = !insertadoUsado && (pre == 0 || (start < pre && pre <= end));
      String antesDelCambio = t.substring(0, clamp(pre - start, t.length()));
      String despuesDelCambio = t.substring(clamp(finAnterior - start, t.length()));
      String reemplazo = antesDelCambio + (destino ? insertado : "") + despuesDelCambio;
      if (destino) {
        insertadoUsado = true;
      }
      if (!reemplazo.equals(t)) {
        escribirRun(run, reemplazo);
      }
      start = end;
    }
  }

  private static void escribirRun(XWPFRun run, String texto) {
    CTR ctr = run.getCTR();
    for (int i = ctr.sizeOfTArray() - 1; i >= 0; i--) {
      ctr.removeT(i);
    }
    for (int i = ctr.sizeOfTabArray() - 1; i >= 0; i--) {
      ctr.removeTab(i);
    }
    for (int i = ctr.sizeOfBrArray() - 1; i >= 0; i--) {
      ctr.removeBr(i);
    }
    for (int i = ctr.sizeOfCrArray() - 1; i >= 0; i--) {
      ctr.removeCr(i);
    }
    StringBuilder buf = new StringBuilder();
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      if (c == '\t' || c == '\n') {
        if (!buf.isEmpty()) {
          run.setText(buf.toString());
          buf.setLength(0);
        }
        if (c == '\t') {
          run.addTab();
        } else {
          run.addBreak();
        }
      } else if (c != '\r') {
        buf.append(c);
      }
    }
    if (!buf.isEmpty()) {
      run.setText(buf.toString());
    }
  }

  private record Ubicado(XWPFParagraph p, boolean enTabla) {}

  private static List<Ubicado> parrafos(XWPFDocument doc) {
    List<Ubicado> out = new ArrayList<>();
    recorrer(doc.getBodyElements(), false, out);
    return out;
  }

  private static void recorrer(List<IBodyElement> elementos, boolean enTabla, List<Ubicado> out) {
    for (IBodyElement el : elementos) {
      if (el instanceof XWPFParagraph p) {
        out.add(new Ubicado(p, enTabla));
      } else if (el instanceof XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
          for (XWPFTableCell cell : row.getTableCells()) {
            recorrer(cell.getBodyElements(), true, out);
          }
        }
      }
    }
  }

  static String texto(XWPFParagraph p) {
    StringBuilder sb = new StringBuilder();
    for (XWPFRun run : p.getRuns()) {
      sb.append(textoRun(run));
    }
    return sb.toString();
  }

  private static String textoRun(XWPFRun run) {
    String t = run.text();
    return t == null ? "" : t;
  }

  private static boolean todoNegrita(XWPFParagraph p) {
    boolean alguno = false;
    for (XWPFRun run : p.getRuns()) {
      if (textoRun(run).isBlank()) {
        continue;
      }
      if (!run.isBold()) {
        return false;
      }
      alguno = true;
    }
    return alguno;
  }

  private static String alineacion(XWPFParagraph p) {
    if (p.getCTP().getPPr() == null || p.getCTP().getPPr().getJc() == null) {
      return null;
    }
    ParagraphAlignment a = p.getAlignment();
    return switch (a) {
      case CENTER -> "CENTER";
      case RIGHT, END -> "RIGHT";
      case BOTH, DISTRIBUTE, LOW_KASHIDA, MEDIUM_KASHIDA, HIGH_KASHIDA, THAI_DISTRIBUTE -> "BOTH";
      default -> "LEFT";
    };
  }

  private static boolean esTitulo(String estilo, String texto, boolean negrita) {
    if (estilo != null) {
      String s = estilo.toLowerCase(Locale.ROOT);
      if (s.startsWith("heading") || s.startsWith("ttulo") || s.startsWith("titulo")
          || s.equals("title")) {
        return true;
      }
    }
    return negrita && !texto.isBlank() && texto.length() <= TITULO_MAX_CHARS;
  }

  private static String valor(Map<String, Object> map, String tag) {
    Object v = map.get(tag);
    return v == null ? "" : v.toString();
  }

  private static String renderizar(String textoPlantilla, Map<String, Object> datos) {
    Matcher m = TAG.matcher(textoPlantilla);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(valor(datos, m.group(1))));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static int contar(String texto, String buscado) {
    if (buscado.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (int i = texto.indexOf(buscado); i >= 0; i = texto.indexOf(buscado, i + buscado.length())) {
      n++;
    }
    return n;
  }

  private static int enesima(String texto, String buscado, int ordinal) {
    if (buscado.isEmpty()) {
      return -1;
    }
    int i = texto.indexOf(buscado);
    for (int n = 0; n < ordinal && i >= 0; n++) {
      i = texto.indexOf(buscado, i + buscado.length());
    }
    return i;
  }

  private static int clamp(int value, int max) {
    return Math.max(0, Math.min(value, max));
  }

  private static XWPFDocument abrir(byte[] bytes) throws IOException {
    return new XWPFDocument(new ByteArrayInputStream(bytes));
  }

  private static byte[] escribir(XWPFDocument doc) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.write(out);
      return out.toByteArray();
    }
  }
}
