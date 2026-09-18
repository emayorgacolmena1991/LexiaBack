package com.lexia.api.modules.expedientes;

public final class GeminiSchemas {

  private GeminiSchemas() {}

  public static final String JSON_SCHEMA =
      """
      {
        "type": "OBJECT",
        "properties": {
          "tipoDocumento": {"type": "STRING"},
          "numeroEscritura": {"type": "STRING"},
          "fechaEscritura": {"type": "STRING"},
          "notaria": {"type": "STRING"},
          "municipio": {"type": "STRING"},
          "comparecientes": {"type": "STRING"},
          "nitIdentificacion": {"type": "STRING"},
          "objetoAsunto": {"type": "STRING"}
        },
        "required": ["tipoDocumento", "numeroEscritura"]
      }
      """;
}
