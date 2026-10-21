package com.lexia.api.modules.expedientes.coactivas.ia;

import java.util.List;
import java.util.Map;

/**
 * Diagnóstico del PDF único.
 *
 * @param etapaNormalizada {@link com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa#name()}
 *     si el texto de la IA mapea a una etapa; si no, null
 */
public record CoactivaDiagnostico(
    int porcentajeCompletitud,
    String etapaDetectada,
    String etapaNormalizada,
    List<Documento> documentos,
    List<String> alertas,
    Map<String, Object> datosExtraidos,
    String siguienteAccion,
    String json) {

  public record Documento(String tipo, Integer fojaInicio, Integer fojaFin, boolean presente) {}
}
