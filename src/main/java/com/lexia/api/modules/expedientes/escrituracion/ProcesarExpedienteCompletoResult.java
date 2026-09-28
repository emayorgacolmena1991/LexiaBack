package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore.DocumentoExtraidoDTO;
import java.util.List;
import java.util.UUID;

/**
 * Respuesta única: extracción por documento (cotejo) + datos consolidados + dictamen. Una llamada
 * LLM por expediente.
 */
public record ProcesarExpedienteCompletoResult(
    UUID expedienteId,
    String productCode,
    String promptKeyUsed,
    List<DocumentoExtraidoDTO> documentosExtraidos,
    DatosExtraidos datosExtraidos,
    Dictamen dictamen) {}
