package com.lexia.api.modules.ia.llm;

import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResultadoCotejoDTO;
import org.springframework.util.StringUtils;

/** Contrato común de extracción/cotejo con LLM. Gemini y Claude lo implementan. */
public interface AnalisisDocumentoService {

  boolean isConfigured();

  ExtraccionDocumento extraerDatosClave(String textoOcr, String tipoDocumento);

  /**
   * Cotejo notarial multi-documento (OCR consolidado). Default: no soportado;
   * Claude lo implementa con tool use {@code cotejar_documentos_expediente}.
   */
  default ExtraccionCotejo cotejarExpediente(String ocrConsolidado) {
    return cotejarExpediente(ocrConsolidado, null, null);
  }

  /**
   * Cotejo por producto BIESS: resuelve prompt vía {@code product_prompt_map} + registry.
   */
  default ExtraccionCotejo cotejarExpediente(
      String ocrConsolidado, String productCode, String canton) {
    return ExtraccionCotejo.error("Cotejo notarial no disponible para este proveedor LLM.");
  }

  /** Mismo record que tenías dentro de GeminiAnalysisService, ahora compartido. */
  record ExtraccionDocumento(
      DatosExtraidosDTO datos, String estado, String motivo, int camposDetectados) {

    public static ExtraccionDocumento fromDatos(DatosExtraidosDTO datos) {
      if (datos == null) {
        return new ExtraccionDocumento(
            DatosExtraidosDTO.empty(), "REVISAR", "No se detectaron campos clave.", 0);
      }
      int claveCount = datos.datosClave() == null ? 0 : datos.datosClave().size();
      boolean tieneTipo = StringUtils.hasText(datos.tipoDocumento());
      boolean tieneResumen = StringUtils.hasText(datos.resumen());
      int score = claveCount + (tieneTipo ? 1 : 0) + (tieneResumen ? 1 : 0);
      if (score == 0) {
        return new ExtraccionDocumento(
            datos, "REVISAR", "No se detectaron campos clave en el texto OCR.", 0);
      }
      if (claveCount == 0) {
        return new ExtraccionDocumento(
            datos, "REVISAR", "Extracción parcial: sin datosClave.", score);
      }
      return new ExtraccionDocumento(datos, "LEGIBLE", null, score);
    }

    public static ExtraccionDocumento error(String motivo) {
      return new ExtraccionDocumento(DatosExtraidosDTO.empty(), "ERROR", motivo, 0);
    }
  }

  record ExtraccionCotejo(ResultadoCotejoDTO resultado, String estado, String motivo) {

    public static ExtraccionCotejo from(ResultadoCotejoDTO resultado) {
      if (resultado == null) {
        return error("Sin resultado de cotejo.");
      }
      return new ExtraccionCotejo(resultado, "OK", null);
    }

    public static ExtraccionCotejo error(String motivo) {
      return new ExtraccionCotejo(
          ResultadoCotejoDTO.error(motivo), "ERROR", motivo);
    }
  }
}
