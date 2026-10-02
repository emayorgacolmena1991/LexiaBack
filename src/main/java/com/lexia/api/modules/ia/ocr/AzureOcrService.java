package com.lexia.api.modules.ia.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/** Módulo 1: Azure Document Intelligence — texto plano ({@code prebuilt-read}) o layout. */
@Service
public class AzureOcrService {

  private final AzureDocumentIntelligenceClient client;

  public AzureOcrService(AzureDocumentIntelligenceClient client) {
    this.client = client;
  }

  public boolean isConfigured() {
    return client.isConfigured();
  }

  /** Extrae solo texto plano del PDF/imagen. */
  public String extraerTexto(byte[] bytesArchivo, String mimeType) {
    JsonNode analyzeResult = client.analyze("prebuilt-read", bytesArchivo, mimeType);
    JsonNode content = analyzeResult.path("content");
    if (content.isMissingNode() || content.isNull()) {
      return "";
    }
    return content.asText("");
  }

  /** {@code analyzeResult} de {@code prebuilt-layout}: content, pages[].lines y tables. */
  public JsonNode analizarLayout(byte[] bytesArchivo, String mimeType) {
    return client.analyze("prebuilt-layout", bytesArchivo, mimeType);
  }
}
