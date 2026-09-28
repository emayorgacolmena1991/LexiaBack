package com.lexia.api.modules.ia.llm;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.prompt.ProductPromptMapRepository;
import com.lexia.api.modules.ia.prompt.PromptRegistryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Una llamada LLM por hash de OCR + producto. {@code analizar-ia} y {@code validar-ia} comparten
 * esta caché para no repetir la extracción.
 */
@Service
public class ExpedienteCompletoLlmService {

  public static final String DEFAULT_PROMPT_KEY = "PROMPT_DEFAULT";

  private final AnalisisDocumentoService analisis;
  private final ProductPromptMapRepository productPromptMapRepository;
  private final PromptRegistryService promptRegistryService;
  private final ConcurrentHashMap<String, ExtraccionExpedienteCompleto> cache =
      new ConcurrentHashMap<>();

  public ExpedienteCompletoLlmService(
      AnalisisDocumentoService analisis,
      ProductPromptMapRepository productPromptMapRepository,
      PromptRegistryService promptRegistryService) {
    this.analisis = analisis;
    this.productPromptMapRepository = productPromptMapRepository;
    this.promptRegistryService = promptRegistryService;
  }

  public Ejecucion ejecutar(
      String ocrMarcado, String productCode, String canton, boolean force) {
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
    String cacheKey = promptKey + "|" + code + "|" + sha(ocrMarcado);
    if (!force) {
      ExtraccionExpedienteCompleto hit = cache.get(cacheKey);
      if (hit != null && "OK".equals(hit.estado())) {
        return new Ejecucion(promptKey, hit, true);
      }
    }

    String prompt =
        promptRegistryService.resolvePrompt(
            promptKey, Map.of("canton", cantonResuelto, "vigenciaDias", "60"));
    ExtraccionExpedienteCompleto ext = analisis.procesarExpedienteCompleto(ocrMarcado, prompt);
    if (ext != null && "OK".equals(ext.estado())) {
      cache.put(cacheKey, ext);
    }
    return new Ejecucion(promptKey, ext, false);
  }

  private static String sha(String text) {
    try {
      byte[] dig =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(dig);
    } catch (Exception e) {
      return Integer.toHexString(text.hashCode());
    }
  }

  public record Ejecucion(
      String promptKey, ExtraccionExpedienteCompleto extraccion, boolean desdeCache) {}
}
