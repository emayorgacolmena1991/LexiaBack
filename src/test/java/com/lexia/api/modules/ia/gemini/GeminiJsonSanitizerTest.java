package com.lexia.api.modules.ia.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.expedientes.ocr.GeminiSchemas;
import org.junit.jupiter.api.Test;

class GeminiJsonSanitizerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void limpiaFenceJsonMarkdown() throws Exception {
    String raw =
        """
        ```json
        {
          "tipoDocumento": "CEDULA",
          "resumen": "Documento de identidad del compareciente.",
          "datosClave": {
            "nombre": "Juan Perez",
            "numero": "010203"
          }
        }
        ```
        """;
    String limpio = GeminiJsonSanitizer.limpiar(raw);
    DatosExtraidosDTO dto = mapper.readValue(limpio, DatosExtraidosDTO.class);
    assertEquals("CEDULA", dto.tipoDocumento());
    assertEquals("Documento de identidad del compareciente.", dto.resumen());
    assertEquals("Juan Perez", dto.datosClave().get("nombre"));
    assertEquals("010203", dto.datosClave().get("numero"));
  }

  @Test
  void schemaEsJsonValido() throws Exception {
    mapper.readTree(GeminiSchemas.JSON_SCHEMA);
    assertTrue(GeminiSchemas.JSON_SCHEMA.contains("datosClave"));
    assertTrue(!GeminiSchemas.JSON_SCHEMA.contains("//"));
  }
}
