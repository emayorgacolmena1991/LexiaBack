package com.lexia.api.modules.expedientes.llm;

import com.lexia.api.modules.expedientes.ExpedienteDtos.DatosExtraidosDTO;
import org.springframework.util.StringUtils;

/** Contrato común de extracción con LLM. Gemini y Claude lo implementan. */
public interface AnalisisDocumentoService {

  boolean isConfigured();

  ExtraccionDocumento extraerDatosClave(String textoOcr, String tipoDocumento);

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
}
