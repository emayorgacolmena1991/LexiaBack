package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import java.util.UUID;

/** Respuesta única de extracción + dictamen. Una llamada LLM por expediente. */
public record ProcesarExpedienteCompletoResult(
    UUID expedienteId,
    String productCode,
    String promptKeyUsed,
    DatosExtraidos datosExtraidos,
    Dictamen dictamen) {}
