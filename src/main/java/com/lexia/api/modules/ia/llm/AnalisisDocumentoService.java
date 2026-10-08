package com.lexia.api.modules.ia.llm;

import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import org.springframework.util.StringUtils;

/** Contrato común de extracción con LLM. Gemini y Claude lo implementan. */
public interface AnalisisDocumentoService {

  boolean isConfigured();

  /**
   * Una sola llamada por expediente: extracción por documento (cotejo) + datos consolidados +
   * dictamen. El prompt ya viene resuelto. El OCR llega marcado por archivo
   * ({@code <documento id="...">}), sin PDF unificado.
   */
  default ExtraccionExpedienteCompleto procesarExpedienteCompleto(
      String ocrMarcado, String systemPrompt) {
    return ExtraccionExpedienteCompleto.error(
        "Procesamiento unificado no disponible para este proveedor LLM.");
  }

  /**
   * Extracción JSON estricta de las variables del acto. Sin producto/plantilla usa el esquema
   * amplio. Default: no soportado.
   */
  default ExtraccionMinutaVivienda extraerMinutaVivienda(String ocrConsolidado) {
    return extraerMinutaVivienda(ocrConsolidado, null, null);
  }

  default ExtraccionMinutaVivienda extraerMinutaVivienda(
      String ocrConsolidado, String productCode, String templateKind) {
    return ExtraccionMinutaVivienda.error(
        "Extracción de minuta vivienda no disponible para este proveedor LLM.");
  }

  /**
   * Captura de pantalla BIESS ("DATOS APROBADOS PARA DESEMBOLSO"): monto, plazo, cuota, tasa,
   * valor de reposición, porcentaje financiado y apoderado. Prompt: {@code PROMPT_BIESS_CAPTURA}.
   * Default: no soportado.
   */
  default ExtraccionCapturaBiess extraerCapturaBiess(String textoCaptura) {
    return ExtraccionCapturaBiess.error(
        "Extracción de captura BIESS no disponible para este proveedor LLM.");
  }

  /**
   * Validación documental (coactivas): system prompt del catálogo + texto OCR de UN documento.
   * El proveedor fuerza salida JSON ({@code response_format: json} / tool use) con el esquema
   * {@code {"estado": "APROBADO|RECHAZADO", "confianza": 0-100, "razon_rechazo": "...",
   * "checklist_cumplido": [...]}} y devuelve el JSON crudo; el parseo lo hace el llamador.
   * Default: no soportado.
   */
  default ValidacionDocumentoJson validarDocumento(String systemPrompt, String textoDocumento) {
    return ValidacionDocumentoJson.error(
        "Validación documental no disponible para este proveedor LLM.");
  }

  /**
   * Diagnóstico del PDF único de coactivas. El prompt ya trae la etapa y el contrato JSON
   * ({@code porcentaje_completitud}, documentos por foja, alertas, siguiente acción). Devuelve el
   * JSON crudo; el parseo lo hace el llamador.
   */
  default ValidacionDocumentoJson diagnosticarCoactiva(String systemPrompt, String textoExpediente) {
    return ValidacionDocumentoJson.error(
        "Diagnóstico de expediente coactivo no disponible para este proveedor LLM.");
  }

  /** JSON crudo del LLM o error de transporte/configuración. */
  record ValidacionDocumentoJson(String json, String error) {

    public static ValidacionDocumentoJson ok(String json) {
      return new ValidacionDocumentoJson(json, null);
    }

    public static ValidacionDocumentoJson error(String motivo) {
      return new ValidacionDocumentoJson(null, motivo);
    }

    public boolean esError() {
      return error != null;
    }
  }

  record ExtraccionCapturaBiess(DatosBiessMinuta data, String estado, String motivo) {

    public static ExtraccionCapturaBiess ok(DatosBiessMinuta data) {
      return new ExtraccionCapturaBiess(
          data == null ? DatosBiessMinuta.empty() : data, "OK", null);
    }

    public static ExtraccionCapturaBiess error(String motivo) {
      return new ExtraccionCapturaBiess(DatosBiessMinuta.empty(), "ERROR", motivo);
    }
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
        return error("Respuesta sin datosConsolidados o dictamen.");
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
              payload.documentosExtraidos(),
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
