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

  /** Solo el modelo liviano. Si hace falta Sonnet, el caller encola {@code IA_ANALISIS_PROFUNDO}. */
  public Ejecucion ejecutar(String ocrMarcado, String productCode, String canton) {
    Resuelto resuelto = resolver(ocrMarcado, productCode, canton);
    var guardado = cache.buscar(resuelto.hash(), resuelto.version());
    if (guardado.isPresent() && StringUtils.hasText(guardado.get().resultadoJson())) {
      try {
        var payload =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(guardado.get().resultadoJson(), ProcesarExpedienteCompletoPayload.class);
        return new Ejecucion(
            resuelto.promptKey(),
            ExtraccionExpedienteCompleto.ok(payload),
            resuelto.version(),
            guardado.get().modelo(),
            resuelto.hash(),
            true);
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        // El JSON guardado no sirve. Se vuelve a llamar al modelo.
      }
    }
    IaConsumoContexto.setEtapa("rapida");
    ExtraccionExpedienteCompleto liviano = analisis.procesarCotejo(resuelto.ocr(), resuelto.prompt());
    guardar(resuelto, liviano, true);
    return new Ejecucion(
        resuelto.promptKey(), liviano, resuelto.version(), modeloDe(liviano), resuelto.hash(), false);
  }

  /** Sonnet fusionado sobre la foto de Haiku ({@code version:rapida}). No la pisa. */
  public Ejecucion ejecutarProfundo(String ocrMarcado, String productCode, String canton) {
    Resuelto resuelto = resolver(ocrMarcado, productCode, canton);
    ProcesarExpedienteCompletoPayload base = leer(resuelto.hash(), resuelto.version() + ":rapida");
    IaConsumoContexto.setEtapa("profunda");
    ExtraccionExpedienteCompleto pesado = analisis.procesarDictamen(resuelto.ocr(), resuelto.prompt());
    if (pesado == null || pesado.payload() == null || !"OK".equals(pesado.estado())) {
      return new Ejecucion(
          resuelto.promptKey(), pesado, resuelto.version(), modeloDe(pesado), resuelto.hash(), false);
    }
    ProcesarExpedienteCompletoPayload fusion =
        AnalisisFusion.fusionar(base, pesado.payload());
    ExtraccionExpedienteCompleto elegido = ExtraccionExpedienteCompleto.ok(fusion);
    guardar(resuelto, elegido, false);
    return new Ejecucion(
        resuelto.promptKey(), elegido, resuelto.version(), modeloDe(pesado), resuelto.hash(), false);
  }

  private void guardar(Resuelto resuelto, ExtraccionExpedienteCompleto elegido, boolean rapida) {
    if (elegido == null || elegido.payload() == null || !"OK".equals(elegido.estado())) {
      return;
    }
    try {
      String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(elegido.payload());
      String modelo = modeloDe(elegido);
      cache.guardar(resuelto.hash(), resuelto.version(), modelo, null, json);
      if (rapida) {
        cache.guardar(resuelto.hash(), resuelto.version() + ":rapida", modelo, null, json);
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      // Sin caché de JSON. El resultado igual se devuelve.
    }
  }

  private ProcesarExpedienteCompletoPayload leer(String hash, String version) {
    var guardado = cache.buscar(hash, version);
    if (guardado.isEmpty() || !StringUtils.hasText(guardado.get().resultadoJson())) {
      return null;
    }
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(guardado.get().resultadoJson(), ProcesarExpedienteCompletoPayload.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      return null;
    }
  }

  private static String modeloDe(ExtraccionExpedienteCompleto extraccion) {
    return IaConsumoContexto.ultimo() == null ? "llm" : IaConsumoContexto.ultimo().modelo();
  }

  private Resuelto resolver(String ocrMarcado, String productCode, String canton) {
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
    return new Resuelto(promptKey, prompt, version, DocumentoCacheStore.sha256(ocrMarcado), ocrMarcado);
  }

  private record Resuelto(String promptKey, String prompt, String version, String hash, String ocr) {}

  public record Ejecucion(
      String promptKey,
      ExtraccionExpedienteCompleto extraccion,
      String versionPrompt,
      String modelo,
      String sha256,
      boolean desdeCache) {
    public Ejecucion(String promptKey, ExtraccionExpedienteCompleto extraccion) {
      this(promptKey, extraccion, promptKey, null, null, false);
    }

    public Ejecucion(
        String promptKey,
        ExtraccionExpedienteCompleto extraccion,
        String versionPrompt,
        String modelo,
        String sha256) {
      this(promptKey, extraccion, versionPrompt, modelo, sha256, false);
    }
  }
}
