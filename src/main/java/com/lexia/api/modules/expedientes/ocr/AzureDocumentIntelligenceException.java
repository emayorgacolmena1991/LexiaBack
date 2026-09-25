package com.lexia.api.modules.expedientes.ocr;

public class AzureDocumentIntelligenceException extends RuntimeException {

  public AzureDocumentIntelligenceException(String message) {
    super(message);
  }

  public AzureDocumentIntelligenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
