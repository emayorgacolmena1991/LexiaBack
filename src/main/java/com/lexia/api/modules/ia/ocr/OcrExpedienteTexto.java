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
      String marcado = desdeResultados(cache.listResults(id));
      if (StringUtils.hasText(marcado)) {
        return marcado;
      }
      String envuelto = envolverPlano(cache.getConsolidated(id));
      if (StringUtils.hasText(envuelto)) {
        return envuelto;
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
