package com.lexia.api.modules.ia.ocr;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoComparacion;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoFuente;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResumen;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * E04: lee caché consolidada (E03) y coteja campos clave entre documentos de la sesión.
 * Usa LLM por sección cuando está configurado; si no, compara textos vacíos → resumen vacío.
 */
@Service
public class OcrCotejoService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrCotejoService.class);

  /** FE: `[Tipo]\n:\n: texto` | BE: `Tipo:\n:\n: texto`. */
  private static final Pattern SECTION =
      Pattern.compile(
          "(?:\\[([^\\]]+)\\]\\s*|([^:\\n\\r\\[]+):)\\s*\\n:\\s*\\n:\\s*", Pattern.MULTILINE);

  private final OcrSessionCacheService cache;
  private final AnalisisDocumentoService analisis;

  public OcrCotejoService(OcrSessionCacheService cache, AnalisisDocumentoService analisis) {
    this.cache = cache;
    this.analisis = analisis;
  }

  public CotejoResponse cotejar(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String id = sessionId.trim();
    String content = cache.getConsolidated(id);
    List<OcrFileResult> results = cache.listResults(id);

    if (content == null && results.isEmpty()) {
      throw ApiException.notFound("Caché OCR no encontrada o expirada para sessionId=" + id);
    }
    if (!StringUtils.hasText(content) && !results.isEmpty()) {
      content = rebuildFromResults(results);
    }
    if (!StringUtils.hasText(content)) {
      return empty(id);
    }

    List<DocSection> sections = parseSections(content, results);
    if (sections.isEmpty()) {
      return empty(id);
    }

    Map<String, Map<String, String>> valuesByField = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();

    for (DocSection section : sections) {
      Map<String, String> datos = extractDatos(section);
      for (Map.Entry<String, String> e : datos.entrySet()) {
        String campo = e.getKey();
        String valor = e.getValue();
        if (!StringUtils.hasText(campo)) {
          continue;
        }
        labels.putIfAbsent(campo, humanLabel(campo));
        valuesByField
            .computeIfAbsent(campo, k -> new LinkedHashMap<>())
            .put(section.documento(), valor);
      }
    }

    if (valuesByField.isEmpty()) {
      LOG.info("Cotejo sessionId={}: sin datosClave extraídos (docs={})", id, sections.size());
      return empty(id);
    }

    List<CotejoComparacion> comparaciones = new ArrayList<>();
    int coinciden = 0;
    int diferencias = 0;
    int noEncontrados = 0;
    int docCount = sections.size();

    for (Map.Entry<String, Map<String, String>> entry : valuesByField.entrySet()) {
      String campo = entry.getKey();
      Map<String, String> porDoc = entry.getValue();
      List<CotejoFuente> fuentes = new ArrayList<>();
      for (DocSection section : sections) {
        String v = porDoc.get(section.documento());
        fuentes.add(new CotejoFuente(section.documento(), v));
      }

      Set<String> presentNorm = new LinkedHashSet<>();
      for (String v : porDoc.values()) {
        if (StringUtils.hasText(v)) {
          presentNorm.add(normalizeValue(v));
        }
      }
      int presentCount = (int) porDoc.values().stream().filter(StringUtils::hasText).count();
      String estado;
      String valorResumen = null;
      String relacion =
          sections.stream().map(DocSection::documento).filter(StringUtils::hasText).reduce((a, b) -> a + " ↔ " + b).orElse("");

      if (docCount > 1 && presentCount < docCount) {
        estado = "NO_ENCONTRADO";
        noEncontrados++;
      } else if (presentNorm.size() > 1) {
        estado = "DIFERENCIA";
        diferencias++;
      } else if (presentNorm.size() == 1) {
        estado = "COINCIDE";
        coinciden++;
        valorResumen = porDoc.values().stream().filter(StringUtils::hasText).findFirst().orElse(null);
      } else {
        estado = "NO_ENCONTRADO";
        noEncontrados++;
      }

      comparaciones.add(
          new CotejoComparacion(
              campo,
              labels.getOrDefault(campo, humanLabel(campo)),
              estado,
              relacion,
              valorResumen,
              fuentes));
    }

    int total = comparaciones.size();
    boolean observacion = diferencias + noEncontrados > 0;
    CotejoResumen resumen =
        new CotejoResumen(total, coinciden, diferencias, noEncontrados, total, observacion);
    return new CotejoResponse(id, resumen, comparaciones);
  }

  private Map<String, String> extractDatos(DocSection section) {
    Map<String, String> out = new LinkedHashMap<>();
    if (!StringUtils.hasText(section.texto())) {
      return out;
    }
    if (analisis != null && analisis.isConfigured()) {
      try {
        ExtraccionDocumento ext = analisis.extraerDatosClave(section.texto(), section.tipo());
        if (ext != null && ext.datos() != null && ext.datos().datosClave() != null) {
          for (Map.Entry<String, Object> e : ext.datos().datosClave().entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
              continue;
            }
            String v = String.valueOf(e.getValue()).trim();
            if (StringUtils.hasText(v) && !"null".equalsIgnoreCase(v)) {
              out.put(e.getKey().trim(), v);
            }
          }
        }
      } catch (Exception ex) {
        LOG.warn(
            "Cotejo extract fail tipo={} err={}", section.tipo(), ex.getMessage());
      }
    }
    return out;
  }

  static List<DocSection> parseSections(String content, List<OcrFileResult> results) {
    List<DocSection> sections = new ArrayList<>();
    Matcher m = SECTION.matcher(content);
    List<int[]> ranges = new ArrayList<>();
    List<String> tipos = new ArrayList<>();
    while (m.find()) {
      String tipo =
          StringUtils.hasText(m.group(1))
              ? m.group(1).trim()
              : (m.group(2) == null ? "DOCUMENTO" : m.group(2).trim());
      tipos.add(tipo);
      ranges.add(new int[] {m.start(), m.end()});
    }
    if (ranges.isEmpty()) {
      sections.add(new DocSection("DOCUMENTO", "DOCUMENTO", content.trim()));
      return sections;
    }
    for (int i = 0; i < ranges.size(); i++) {
      int textStart = ranges.get(i)[1];
      int textEnd = i + 1 < ranges.size() ? ranges.get(i + 1)[0] : content.length();
      String texto = content.substring(textStart, textEnd).trim();
      String tipo = tipos.get(i);
      String documento = resolveDocName(tipo, i, results);
      sections.add(new DocSection(documento, tipo, texto));
    }
    return sections;
  }

  private static String resolveDocName(String tipo, int index, List<OcrFileResult> results) {
    if (results != null) {
      for (OcrFileResult r : results) {
        if (r == null) {
          continue;
        }
        String rTipo = r.tipoDocumento() == null ? "" : r.tipoDocumento().trim();
        if (rTipo.equalsIgnoreCase(tipo) || tipo.equalsIgnoreCase("[" + rTipo + "]")) {
          if (StringUtils.hasText(r.fileName())) {
            return r.fileName().trim();
          }
          if (StringUtils.hasText(rTipo)) {
            return rTipo;
          }
        }
      }
      if (index >= 0 && index < results.size()) {
        OcrFileResult r = results.get(index);
        if (r != null && StringUtils.hasText(r.fileName())) {
          return r.fileName().trim();
        }
        if (r != null && StringUtils.hasText(r.tipoDocumento())) {
          return r.tipoDocumento().trim();
        }
      }
    }
    return StringUtils.hasText(tipo) ? tipo : ("Documento " + (index + 1));
  }

  private static String rebuildFromResults(List<OcrFileResult> results) {
    StringBuilder sb = new StringBuilder();
    for (OcrFileResult r : results) {
      if (r == null || !r.legible()) {
        continue;
      }
      String tipo = StringUtils.hasText(r.tipoDocumento()) ? r.tipoDocumento() : "DOCUMENTO";
      sb.append('[').append(tipo).append("]\n:\n: ");
      sb.append(r.textoExtraido() == null ? "" : r.textoExtraido().trim());
      sb.append("\n\n");
    }
    return sb.toString().trim();
  }

  private static CotejoResponse empty(String sessionId) {
    return new CotejoResponse(
        sessionId,
        new CotejoResumen(0, 0, 0, 0, 0, false),
        List.of());
  }

  static String normalizeValue(String raw) {
    String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s._\\-/]+", " ")
        .trim();
    return s;
  }

  static String humanLabel(String campo) {
    String spaced =
        campo
            .replace('_', ' ')
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .trim();
    if (!StringUtils.hasText(spaced)) {
      return campo;
    }
    return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
  }

  record DocSection(String documento, String tipo, String texto) {}
}
