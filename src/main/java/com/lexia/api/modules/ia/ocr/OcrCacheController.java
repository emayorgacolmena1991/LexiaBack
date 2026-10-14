package com.lexia.api.modules.ia.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaJobQueue;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidatedGetResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E03/E04: caché consolidada de textos OCR + cotejo IA por sesión. */
@RestController
@RequestMapping("/api/v1/cache")
public class OcrCacheController {

  private static final Logger LOG = LoggerFactory.getLogger(OcrCacheController.class);

  private final OcrAzureBatchService batchService;
  private final OcrCotejoService cotejoService;
  private final IngestaJobQueue jobs;
  private final ObjectMapper json;

  public OcrCacheController(
      OcrAzureBatchService batchService,
      OcrCotejoService cotejoService,
      IngestaJobQueue jobs,
      ObjectMapper json) {
    this.batchService = batchService;
    this.cotejoService = cotejoService;
    this.jobs = jobs;
    this.json = json;
  }

  @PostMapping("/consolidate-extracted-text")
  public ConsolidateResponse consolidate(@Valid @RequestBody ConsolidateRequest request) {
    ConsolidateResponse respuesta = batchService.consolidate(request);
    encolarAnalisis(request);
    return respuesta;
  }

  @GetMapping("/consolidate-extracted-text/{sessionId}")
  public ConsolidatedGetResponse get(@PathVariable String sessionId) {
    return batchService.getConsolidated(sessionId);
  }

  /**
   * E04 — coteja con la extracción persistida por analizar-ia (sin LLM). {@code caseId} es el
   * expediente cuando difiere del borrador {@code sessionId}.
   */
  @GetMapping("/cotejo/{sessionId}")
  public ResponseEntity<CotejoResponse> cotejo(
      @PathVariable String sessionId, @RequestParam(required = false) String caseId) {
    CotejoResponse body = cotejoService.cotejar(sessionId, caseId);
    if (!"SIN_EXTRACCION".equals(body.estado())) {
      return ResponseEntity.ok(body);
    }
    String expediente = caseId != null && !caseId.isBlank() ? caseId : sessionId;
    AuthPrincipal principal = AuthContext.require();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("expedienteId", expediente);
    payload.put("forceReanalysis", false);
    payload.put("sessionIdOcr", sessionId);
    payload.put("userId", principal.userId() == null ? "" : principal.userId().toString());
    payload.put("sessionId", principal.sessionId() == null ? "" : principal.sessionId().toString());
    payload.put("membershipId", principal.membershipId() == null ? "" : principal.membershipId().toString());
    String cuerpo;
    try {
      cuerpo = json.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudo encolar el análisis.");
    }
    UUID jobId = jobs.encolarSiActivo(principal.tenantId(), "ESCRITURACION_ANALISIS", expediente, cuerpo);
    return ResponseEntity.accepted()
        .body(
            new CotejoResponse(
                body.sessionId(), body.resumen(), body.comparaciones(), body.grupos(), "EN_ANALISIS", jobId));
  }

  /** El texto ya quedó en caché. El job corre el LLM; el stream no tiene que esperar un POST aparte. */
  private void encolarAnalisis(ConsolidateRequest request) {
    if (request == null || request.sessionId() == null || request.consolidatedContent() == null
        || request.consolidatedContent().isBlank()) {
      return;
    }
    UUID expediente;
    try {
      expediente = UUID.fromString(request.sessionId().trim());
    } catch (IllegalArgumentException ex) {
      return;
    }
    AuthPrincipal principal = AuthContext.get();
    if (principal == null || principal.tenantId() == null) {
      LOG.warn("OCR consolidado de {} sin sesión; no se encoló la IA", expediente);
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("expedienteId", expediente.toString());
    payload.put("force", true);
    payload.put("forceReanalysis", true);
    payload.put("sessionIdOcr", expediente.toString());
    payload.put("userId", principal.userId() == null ? "" : principal.userId().toString());
    payload.put("sessionId", principal.sessionId() == null ? "" : principal.sessionId().toString());
    payload.put("membershipId", principal.membershipId() == null ? "" : principal.membershipId().toString());
    try {
      jobs.encolarSiActivo(
          principal.tenantId(), "ESCRITURACION_ANALISIS", expediente.toString(), json.writeValueAsString(payload));
      LOG.info(
          "[OCR OK] Texto consolidado para expediente {}. Encolando job ESCRITURACION_ANALISIS en app.jobs...",
          expediente);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudo encolar el análisis.");
    }
  }
}
