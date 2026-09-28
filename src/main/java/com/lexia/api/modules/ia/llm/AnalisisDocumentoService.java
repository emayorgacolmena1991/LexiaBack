package com.lexia.api.modules.ia.llm;

import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import org.springframework.util.StringUtils;

/** Contrato común de extracción con LLM. Gemini y Claude lo implementan. */
public interface AnalisisDocumentoService {

  boolean isConfigured();

  ExtraccionDocumento extraerDatosClave(String textoOcr, String tipoDocumento);

  /**
   * Una sola llamada: variables de minuta + dictamen. El prompt ya viene resuelto.
   * El OCR llega marcado por archivo ({@code <documento id="...">}), sin PDF unificado.
   */
  default ExtraccionExpedienteCompleto procesarExpedienteCompleto(
      String ocrMarcado, String systemPrompt) {
    return ExtraccionExpedienteCompleto.error(
        "Procesamiento unificado no disponible para este proveedor LLM.");
  }

  /**
   * Extracción estructurada para minuta vivienda hipotecada (tool use JSON estricto).
   * Default: no soportado.
   */
  default ExtraccionMinutaVivienda extraerMinutaVivienda(String ocrConsolidado) {
    return ExtraccionMinutaVivienda.error(
        "Extracción de minuta vivienda no disponible para este proveedor LLM.");
  }

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

  record ExtraccionExpedienteCompleto(
      ProcesarExpedienteCompletoPayload payload, String estado, String motivo) {

    public static ExtraccionExpedienteCompleto ok(ProcesarExpedienteCompletoPayload payload) {
      if (payload == null || payload.datosExtraidos() == null || payload.dictamen() == null) {
        return error("Respuesta sin datosExtraidos o dictamen.");
      }
      String estado = payload.dictamen().estado() == null ? "" : payload.dictamen().estado().trim();
      String normalizado =
          "APPROVED".equalsIgnoreCase(estado) || "APROBADO".equalsIgnoreCase(estado)
              ? "APPROVED"
              : "REJECTED".equalsIgnoreCase(estado) || "RECHAZADO".equalsIgnoreCase(estado)
                  ? "REJECTED"
                  : "WITH_OBSERVATIONS";
      ProcesarExpedienteCompletoPayload listo =
          new ProcesarExpedienteCompletoPayload(
              payload.datosExtraidos(),
              new ProcesarExpedienteCompletoPayload.Dictamen(
                  normalizado, payload.dictamen().resumen(), payload.dictamen().observaciones()));
      return new ExtraccionExpedienteCompleto(listo, "OK", null);
    }

    public static ExtraccionExpedienteCompleto error(String motivo) {
      return new ExtraccionExpedienteCompleto(null, "ERROR", motivo);
    }
  }

  record ExtraccionMinutaVivienda(MinutaViviendaData data, String estado, String motivo) {

    public static ExtraccionMinutaVivienda ok(MinutaViviendaData data) {
      return new ExtraccionMinutaVivienda(
          data == null ? new MinutaViviendaData() : data, "OK", null);
    }

    public static ExtraccionMinutaVivienda error(String motivo) {
      return new ExtraccionMinutaVivienda(new MinutaViviendaData(), "ERROR", motivo);
    }
  }
}
