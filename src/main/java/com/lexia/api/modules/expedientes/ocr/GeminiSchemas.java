package com.lexia.api.modules.expedientes.ocr;

public final class GeminiSchemas {

  private GeminiSchemas() {}

  /** Schema dinámico: tipoDocumento + resumen + datosClave (objeto libre). */
  public static final String JSON_SCHEMA =
      """
      {
        "type": "OBJECT",
        "properties": {
          "tipoDocumento": {"type": "STRING"},
          "resumen": {"type": "STRING"},
          "datosClave": {
            "type": "OBJECT",
            "additionalProperties": {}
          }
        },
        "required": ["tipoDocumento", "resumen", "datosClave"]
      }
      """;
}
