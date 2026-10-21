package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCapturaBiess;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Lee una captura de la plataforma BIESS (Azure prebuilt-layout → texto "etiqueta: valor" → LLM en
 * modo JSON/tool) y devuelve monto,
 * tasa, plazo, cuota y apoderado. La captura no es un documento del expediente: no entra al cotejo
 * ni a la caché OCR. Los cinco campos sí se guardan en {@code extracted_data} (grupo {@code biess}).
 */
@Service
public class CapturaBiessService {

  private static final Logger LOG = LoggerFactory.getLogger(CapturaBiessService.class);
  private static final long MAX_BYTES = 10L * 1024 * 1024;
  private static final Map<String, String> MIME_BY_EXTENSION =
      Map.of(
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "png", "image/png",
          "pdf", "application/pdf");

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final AzureOcrService ocr;
  private final AnalisisDocumentoService analisis;
  private final DatosBiessStore datosBiess;

  public CapturaBiessService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      AzureOcrService ocr,
      AnalisisDocumentoService analisis,
      DatosBiessStore datosBiess) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.ocr = ocr;
    this.analisis = analisis;
    this.datosBiess = datosBiess;
  }

  public DatosBiessMinuta extraer(UUID caseId, MultipartFile file) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));

    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Adjunta la captura BIESS (JPG, PNG o PDF).");
    }
    if (file.getSize() > MAX_BYTES) {
      throw ApiException.badRequest("La captura BIESS supera el máximo de 10 MB.");
    }
    String mime = resolveMime(file);
    if (!ocr.isConfigured()) {
      throw ApiException.badRequest("Azure Document Intelligence no está configurado.");
    }
    if (!analisis.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para leer la captura BIESS.");
    }

    JsonNode layout;
    try {
      layout = ocr.analizarLayout(file.getBytes(), mime);
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el archivo de la captura BIESS.");
    } catch (RuntimeException e) {
      LOG.warn("OCR captura BIESS falló case={}: {}", caseId, e.getMessage());
      throw ApiException.badRequest("No se pudo leer el texto de la captura BIESS.");
    }

    String texto = textoEtiquetaValor(layout);
    LOG.info("Captura BIESS case={} texto enviado al LLM:\n{}", caseId, texto);

    ExtraccionCapturaBiess extraccion = analisis.extraerCapturaBiess(texto);
    if (extraccion == null || "ERROR".equals(extraccion.estado())) {
      throw ApiException.badRequest(
          extraccion != null && StringUtils.hasText(extraccion.motivo())
              ? extraccion.motivo()
              : "No se pudieron extraer datos de la captura BIESS.");
    }
    DatosBiessMinuta data = extraccion.data() == null ? DatosBiessMinuta.empty() : extraccion.data();
    if (data.isEmpty()) {
      throw ApiException.badRequest(
          "No se pudo leer la captura BIESS: no se encontraron monto, tasa, plazo, cuota ni"
              + " apoderado. Sube una imagen más nítida de DATOS APROBADOS PARA DESEMBOLSO.");
    }
    datosBiess.guardar(tenantId, caseId, data);
    LOG.info("Captura BIESS persistida case={} bytes={}", caseId, file.getSize());
    return data;
  }

  /**
   * Texto "etiqueta: valor" para el LLM: filas de tablas de Azure; si no hay tablas, líneas unidas
   * por fila visual (polygon); si tampoco hay líneas, el content plano.
   */
  static String textoEtiquetaValor(JsonNode layout) {
    if (layout == null || layout.isMissingNode()) {
      return "";
    }
    String tablas = textoDeTablas(layout.path("tables"));
    if (!tablas.isBlank()) {
      return tablas;
    }
    String lineas = textoDeLineas(layout.path("pages"));
    if (!lineas.isBlank()) {
      return lineas;
    }
    return layout.path("content").asText("").trim();
  }

  private static String textoDeTablas(JsonNode tables) {
    StringBuilder out = new StringBuilder();
    for (JsonNode table : tables) {
      Map<Integer, TreeMap<Integer, String>> filas = new TreeMap<>();
      for (JsonNode cell : table.path("cells")) {
        String contenido = limpiar(cell.path("content").asText(""));
        if (contenido.isEmpty()) {
          continue;
        }
        filas
            .computeIfAbsent(cell.path("rowIndex").asInt(), k -> new TreeMap<>())
            .put(cell.path("columnIndex").asInt(), contenido);
      }
      for (TreeMap<Integer, String> fila : filas.values()) {
        appendFila(out, new ArrayList<>(fila.values()));
      }
    }
    return out.toString().trim();
  }

  private static String textoDeLineas(JsonNode pages) {
    StringBuilder out = new StringBuilder();
    for (JsonNode page : pages) {
      List<LineaOcr> lineas = new ArrayList<>();
      for (JsonNode line : page.path("lines")) {
        LineaOcr l = LineaOcr.of(line);
        if (l != null) {
          lineas.add(l);
        }
      }
      lineas.sort(Comparator.comparingDouble(LineaOcr::centroY));
      List<LineaOcr> fila = new ArrayList<>();
      double filaY = 0;
      double filaAlto = 0;
      for (LineaOcr l : lineas) {
        double tolerancia = Math.max(filaAlto, l.alto()) / 2;
        if (!fila.isEmpty() && Math.abs(l.centroY() - filaY) > tolerancia) {
          appendFilaVisual(out, fila);
          fila = new ArrayList<>();
        }
        if (fila.isEmpty()) {
          filaY = l.centroY();
          filaAlto = l.alto();
        }
        fila.add(l);
      }
      appendFilaVisual(out, fila);
    }
    return out.toString().trim();
  }

  private static void appendFilaVisual(StringBuilder out, List<LineaOcr> fila) {
    fila.sort(Comparator.comparingDouble(LineaOcr::minX));
    appendFila(out, fila.stream().map(LineaOcr::texto).toList());
  }

  private static void appendFila(StringBuilder out, List<String> celdas) {
    if (celdas.isEmpty()) {
      return;
    }
    StringBuilder fila = new StringBuilder(celdas.get(0));
    for (int i = 1; i < celdas.size(); i++) {
      fila.append(fila.charAt(fila.length() - 1) == ':' ? " " : ": ").append(celdas.get(i));
    }
    out.append(fila).append('\n');
  }

  private static String limpiar(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim();
  }

  private record LineaOcr(String texto, double minX, double centroY, double alto) {

    /** Acepta {@code polygon} (API 2023+) o {@code boundingBox} (v2.x): [x1,y1,...,x4,y4]. */
    static LineaOcr of(JsonNode line) {
      String texto = limpiar(line.path("content").asText(line.path("text").asText("")));
      JsonNode poly = line.has("polygon") ? line.path("polygon") : line.path("boundingBox");
      if (texto.isEmpty() || !poly.isArray() || poly.size() < 8) {
        return null;
      }
      double minX = Double.MAX_VALUE;
      double minY = Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;
      for (int i = 0; i + 1 < poly.size(); i += 2) {
        minX = Math.min(minX, poly.get(i).asDouble());
        minY = Math.min(minY, poly.get(i + 1).asDouble());
        maxY = Math.max(maxY, poly.get(i + 1).asDouble());
      }
      return new LineaOcr(texto, minX, (minY + maxY) / 2, maxY - minY);
    }
  }

  private static String resolveMime(MultipartFile file) {
    String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    String mime = MIME_BY_EXTENSION.get(ext);
    if (mime == null) {
      throw ApiException.badRequest("Formato no permitido. Solo JPG, PNG o PDF.");
    }
    return mime;
  }
}
