package com.lexia.api.modules.ia.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Check calidad + texto vía Azure {@code prebuilt-layout} (1 llamada F0-friendly).
 * Promedio confidence &lt; 0.75 → no legible.
 */
@Service
public class AzureCalidadDocumentoService {

  private final AzureDocumentIntelligenceClient client;

  public AzureCalidadDocumentoService(AzureDocumentIntelligenceClient client) {
    this.client = client;
  }

  public boolean isConfigured() {
    return client.isConfigured();
  }

  public CalidadDocumentoResultado evaluar(byte[] bytesArchivo, String mimeType) {
    return evaluarYExtraer(bytesArchivo, mimeType).calidad();
  }

  /** Una sola llamada layout: confidence + content. */
  public LayoutExtractResult evaluarYExtraer(byte[] bytesArchivo, String mimeType) {
    JsonNode analyzeResult = client.analyze("prebuilt-layout", bytesArchivo, mimeType);
    CalidadDocumentoResultado calidad = calcularCalidad(analyzeResult);
    String texto = textoDe(analyzeResult);
    if (!calidad.legible()) {
      return new LayoutExtractResult(calidad, "");
    }
    return new LayoutExtractResult(calidad, texto);
  }

  private static String textoDe(JsonNode analyzeResult) {
    JsonNode content = analyzeResult.path("content");
    if (content.isMissingNode() || content.isNull()) {
      return "";
    }
    return content.asText("");
  }

  private static CalidadDocumentoResultado calcularCalidad(JsonNode analyzeResult) {
    List<Double> confianzas = new ArrayList<>();
    JsonNode pages = analyzeResult.path("pages");
    if (pages.isArray()) {
      for (JsonNode page : pages) {
        JsonNode words = page.path("words");
        if (!words.isArray()) {
          continue;
        }
        for (JsonNode word : words) {
          JsonNode conf = word.path("confidence");
          if (conf.isNumber()) {
            confianzas.add(conf.asDouble());
          }
        }
      }
    }

    if (confianzas.isEmpty()) {
      return CalidadDocumentoResultado.ilegible();
    }

    double suma = 0.0;
    for (double c : confianzas) {
      suma += c;
    }
    double promedio = suma / confianzas.size();
    return CalidadDocumentoResultado.dePromedio(promedio, confianzas.size());
  }
}
