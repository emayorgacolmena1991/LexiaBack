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
    EscrituracionPromptCatalog.Prompt deBd = catalogo.resolver(cantonResuelto, code, "estudio_titulo");
    String prompt =
        deBd != null
            ? deBd.texto().replace("${canton}", cantonResuelto).replace("${productCode}", code)
            : promptRegistryService.resolvePrompt(
                promptKey, Map.of("canton", cantonResuelto, "productCode", code, "vigenciaDias", "60"));
    String version =
        promptKey + "@v" + (deBd == null ? 0 : deBd.version()) + ":"
            + DocumentoCacheStore.sha256(prompt).substring(0, 12);
    String hash = DocumentoCacheStore.sha256(ocrMarcado);
    var guardado = cache.buscar(hash, version);
    if (guardado.isPresent() && StringUtils.hasText(guardado.get().resultadoJson())) {
      try {
        var payload =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(guardado.get().resultadoJson(), ProcesarExpedienteCompletoPayload.class);
        return new Ejecucion(
            promptKey, ExtraccionExpedienteCompleto.ok(payload), version, guardado.get().modelo(), hash);
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
    String modelo = IaConsumoContexto.ultimo() == null ? "llm" : IaConsumoContexto.ultimo().modelo();
    if (elegido != null && elegido.payload() != null && "OK".equals(elegido.estado())) {
      try {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(elegido.payload());
        cache.guardar(hash, version, modelo, null, json);
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        // Sin caché de JSON. El resultado igual se devuelve.
      }
    }
    return new Ejecucion(promptKey, elegido, version, modelo, hash);
  }

  public record Ejecucion(
      String promptKey,
      ExtraccionExpedienteCompleto extraccion,
      String versionPrompt,
      String modelo,
      String sha256) {
    public Ejecucion(String promptKey, ExtraccionExpedienteCompleto extraccion) {
      this(promptKey, extraccion, promptKey, null, null);
    }
  }
}
