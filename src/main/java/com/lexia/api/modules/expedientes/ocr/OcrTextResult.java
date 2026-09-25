package com.lexia.api.modules.expedientes.ocr;

/** Texto OCR interno. No es contrato HTTP; Gemini lo consumirá en una fase posterior. */
public record OcrTextResult(String text, String modelId, int pageCount) {

  static OcrTextResult empty(String modelId) {
    return new OcrTextResult("", modelId, 0);
  }
}
