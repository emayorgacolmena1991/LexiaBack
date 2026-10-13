package com.lexia.api.modules.ia.llm;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionPromptCatalog;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.ocr.DocumentoCacheStore;
import com.lexia.api.modules.ia.prompt.ProductPromptMapRepository;
import com.lexia.api.modules.ia.prompt.PromptRegistryService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resuelve el prompt del producto y ejecuta la única llamada LLM del expediente. Sin caché en
 * memoria: la reutilización sale de lo persistido en BD ({@code title_study}/{@code extracted_data}).
 */
@Service
public class ExpedienteCompletoLlmService {

  public static final String DEFAULT_PROMPT_KEY = "PROMPT_DEFAULT";

  private final AnalisisDocumentoService analisis;
  private final ProductPromptMapRepository productPromptMapRepository;
  private final PromptRegistryService promptRegistryService;
  private final EscrituracionPromptCatalog catalogo;
  private final DocumentoCacheStore cache;

  public ExpedienteCompletoLlmService(
      AnalisisDocumentoService analisis,
      ProductPromptMapRepository productPromptMapRepository,
      PromptRegistryService promptRegistryService,
      EscrituracionPromptCatalog catalogo,
      DocumentoCacheStore cache) {
    this.analisis = analisis;
    this.productPromptMapRepository = productPromptMapRepository;
    this.promptRegistryService = promptRegistryService;
    this.catalogo = catalogo;
    this.cache = cache;
  }

  public Ejecucion ejecutar(String ocrMarcado, String productCode, String canton) {
    if (!StringUtils.hasText(ocrMarcado)) {
      throw ApiException.badRequest("Sin texto OCR para procesar el expediente.");
    }
    if (!analisis.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para análisis IA.");
    }
    String code = StringUtils.hasText(productCode) ? productCode.trim() : "";
    String promptKey =
        StringUtils.hasText(code)
            ? productPromptMapRepository
                .findPromptKeyByProductCode(code)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_PROMPT_KEY)
            : DEFAULT_PROMPT_KEY;
    String cantonResuelto = StringUtils.hasText(canton) ? canton.trim() : "GUAYAQUIL";
    String deBd = catalogo.resolver(cantonResuelto, code, "*");
    String prompt =
        StringUtils.hasText(deBd)
            ? deBd.replace("${canton}", cantonResuelto).replace("${productCode}", code)
            : promptRegistryService.resolvePrompt(
                promptKey, Map.of("canton", cantonResuelto, "productCode", code, "vigenciaDias", "60"));
    String hash = DocumentoCacheStore.sha256(ocrMarcado);
    var guardado = cache.buscar(hash, promptKey);
    if (guardado.isPresent() && StringUtils.hasText(guardado.get().resultadoJson())) {
      try {
        var payload =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(guardado.get().resultadoJson(), ProcesarExpedienteCompletoPayload.class);
        return new Ejecucion(promptKey, ExtraccionExpedienteCompleto.ok(payload));
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        // El JSON guardado no sirve. Se vuelve a llamar al modelo.
      }
    }
    ExtraccionExpedienteCompleto liviano = analisis.procesarCotejo(ocrMarcado, prompt);
    ExtraccionExpedienteCompleto elegido = liviano;
    if (IaModelRouting.necesitaPesado(liviano)) {
      ExtraccionExpedienteCompleto pesado = analisis.procesarDictamen(ocrMarcado, prompt);
      if (pesado != null && "OK".equals(pesado.estado())) {
        elegido = pesado;
      }
    }
    if (elegido != null && elegido.payload() != null && "OK".equals(elegido.estado())) {
      try {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(elegido.payload());
        String modelo = IaConsumoContexto.ultimo() == null ? "llm" : IaConsumoContexto.ultimo().modelo();
        cache.guardar(hash, promptKey, modelo, null, json);
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        // Sin caché de JSON. El resultado igual se devuelve.
      }
    }
    return new Ejecucion(promptKey, elegido);
  }

  public record Ejecucion(String promptKey, ExtraccionExpedienteCompleto extraccion) {}
}
