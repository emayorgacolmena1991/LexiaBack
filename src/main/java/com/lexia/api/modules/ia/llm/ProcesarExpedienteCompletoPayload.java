package com.lexia.api.modules.ia.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** JSON de la tool {@code procesar_expediente_completo}. Una sola respuesta del LLM. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcesarExpedienteCompletoPayload(DatosExtraidos datosExtraidos, Dictamen dictamen) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DatosExtraidos(Persona comprador, Persona vendedor, Inmueble inmueble) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Persona(String nombres, String cedula, String estadoCivil) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Inmueble(String claveCatastral, String linderos, Double avaluo) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Dictamen(String estado, String resumen, List<Observacion> observaciones) {

    public List<Observacion> observaciones() {
      return observaciones == null ? List.of() : observaciones;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Observacion(String codigo, String severidad, String mensaje) {}
}
