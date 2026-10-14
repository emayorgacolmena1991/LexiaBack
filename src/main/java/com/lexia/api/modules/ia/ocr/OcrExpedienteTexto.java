package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Une el texto OCR ya extraído por archivo. No fusiona PDFs.
 * Cada archivo sigue siendo un {@code <documento id="...">} dentro del payload a Claude.
 */
public final class OcrExpedienteTexto {

  private OcrExpedienteTexto() {}

  public record DocOcr(String id, String tipo, String nombre, String texto) {}

  public static String paraClaude(OcrSessionCacheService cache, List<String> sessionIds) {
    if (cache == null || sessionIds == null) {
      return "";
    }
    for (String raw : sessionIds) {
      if (!StringUtils.hasText(raw)) {
        continue;
      }
      String id = raw.trim();
      String elegido =
          masDocumentos(desdeResultados(cache.listResults(id)), marcarConsolidado(cache.getConsolidated(id)));
      if (StringUtils.hasText(elegido)) {
        return elegido;
      }
    }
    return "";
  }

  public static String desdeResultados(List<OcrFileResult> results) {
    if (results == null || results.isEmpty()) {
      return "";
    }
    List<DocOcr> docs =
        results.stream()
            .filter(r -> r != null && r.legible() && StringUtils.hasText(r.textoExtraido()))
            .map(
                r ->
                    new DocOcr(
                        r.fileId(), r.tipoDocumento(), r.fileName(), r.textoExtraido()))
            .toList();
    return documentos(docs);
  }

  public static String documentos(List<DocOcr> docs) {
    if (docs == null || docs.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("<expediente_ocr>\n");
    int n = 0;
    for (DocOcr doc : docs) {
      if (doc == null || !StringUtils.hasText(doc.texto())) {
        continue;
      }
      n++;
      String id = StringUtils.hasText(doc.id()) ? doc.id().trim() : "doc-" + n;
      sb.append("<documento id=\"").append(xml(id)).append("\"");
      if (StringUtils.hasText(doc.tipo())) {
        sb.append(" tipo=\"").append(xml(doc.tipo().trim())).append("\"");
      }
      if (StringUtils.hasText(doc.nombre())) {
        sb.append(" nombre=\"").append(xml(doc.nombre().trim())).append("\"");
      }
      sb.append(">\n");
      sb.append(doc.texto().trim());
      sb.append("\n</documento>\n");
    }
    if (n == 0) {
      return "";
    }
    sb.append("</expediente_ocr>");
    return sb.toString();
  }

  private static final Pattern MARCA_DOCUMENTO =
      Pattern.compile("===\\s*DOCUMENTO:\\s*([^=\\n]+?)\\s*===");

  /** El texto con más bloques gana. Un consolidado tardío de un solo PDF no pisa el lote. */
  public static String masDocumentos(String porArchivo, String consolidado) {
    if (contarDocumentos(consolidado) > contarDocumentos(porArchivo) && StringUtils.hasText(consolidado)) {
      return consolidado;
    }
    if (StringUtils.hasText(porArchivo)) {
      return porArchivo;
    }
    return consolidado == null ? "" : consolidado;
  }

  /** {@code === DOCUMENTO: TIPO ===} del front pasa a un {@code <documento>} por archivo. */
  public static String marcarConsolidado(String texto) {
    List<DocOcr> partes = partirConsolidado(texto);
    if (partes.size() > 1) {
      return documentos(partes);
    }
    return envolverPlano(texto);
  }

  public static int contarDocumentos(String texto) {
    if (!StringUtils.hasText(texto)) {
      return 0;
    }
    int tags = 0;
    for (int i = 0; (i = texto.indexOf("<documento", i)) >= 0; i += 10) {
      tags++;
    }
    int marcas = 0;
    Matcher m = MARCA_DOCUMENTO.matcher(texto);
    while (m.find()) {
      marcas++;
    }
    return Math.max(tags, marcas);
  }

  static List<DocOcr> partirConsolidado(String texto) {
    List<DocOcr> out = new ArrayList<>();
    if (!StringUtils.hasText(texto)) {
      return out;
    }
    Matcher m = MARCA_DOCUMENTO.matcher(texto);
    List<Integer> marcas = new ArrayList<>();
    List<Integer> cuerpos = new ArrayList<>();
    List<String> tipos = new ArrayList<>();
    while (m.find()) {
      marcas.add(m.start());
      cuerpos.add(m.end());
      tipos.add(m.group(1).trim());
    }
    for (int i = 0; i < cuerpos.size(); i++) {
      int fin = i + 1 < marcas.size() ? marcas.get(i + 1) : texto.length();
      String cuerpo = texto.substring(cuerpos.get(i), fin).trim();
      if (!StringUtils.hasText(cuerpo)) {
        continue;
      }
      out.add(new DocOcr("doc-" + (i + 1), tipos.get(i), null, cuerpo));
    }
    return out;
  }

  public static String envolverPlano(String texto) {
    if (!StringUtils.hasText(texto)) {
      return "";
    }
    String body = texto.trim();
    if (body.contains("<documento")) {
      return body.contains("<expediente_ocr>")
          ? body
          : "<expediente_ocr>\n" + body + "\n</expediente_ocr>";
    }
    return "<expediente_ocr>\n<documento id=\"consolidado\">\n"
        + body
        + "\n</documento>\n</expediente_ocr>";
  }

  /** Cabeceras {@code <documento id tipo nombre>} del texto marcado, en orden (sin texto). */
  public static List<DocOcr> cabeceras(String marcado) {
    List<DocOcr> out = new ArrayList<>();
    if (!StringUtils.hasText(marcado)) {
      return out;
    }
    Matcher m = CABECERA.matcher(marcado);
    while (m.find()) {
      String attrs = m.group(1);
      out.add(
          new DocOcr(atributo(attrs, "id"), atributo(attrs, "tipo"), atributo(attrs, "nombre"), null));
    }
    return out;
  }

  private static final Pattern CABECERA = Pattern.compile("<documento\\b([^>]*)>");

  private static String atributo(String attrs, String nombre) {
    Matcher m = Pattern.compile("\\b" + nombre + "=\"([^\"]*)\"").matcher(attrs);
    return m.find() ? unxml(m.group(1)) : null;
  }

  static String xml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private static String unxml(String value) {
    return value
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&");
  }
}
