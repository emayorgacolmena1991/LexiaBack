package com.lexia.api.modules.ia.llm;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
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
  static final String CANTON_NO_INDICADO = "NO INDICADO";
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ExpedienteCompletoLlmService.class);

  private final AnalisisDocumentoService analisis;
  private final ProductPromptMapRepository productPromptMapRepository;
  private final PromptRegistryService promptRegistryService;

  public ExpedienteCompletoLlmService(
      AnalisisDocumentoService analisis,
      ProductPromptMapRepository productPromptMapRepository,
      PromptRegistryService promptRegistryService) {
    this.analisis = analisis;
    this.productPromptMapRepository = productPromptMapRepository;
    this.promptRegistryService = promptRegistryService;
  }

  public Ejecucion ejecutar(String ocrMarcado, String productCode, String canton) {
    return ejecutar(ocrMarcado, productCode, canton, "");
  }

  public Ejecucion ejecutar(
      String ocrMarcado, String productCode, String canton, String contextoExpediente) {
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
    // Sin cantón no se asume Guayaquil: el prompt lo dice y solo aplican requisitos generales.
    String cantonResuelto = StringUtils.hasText(canton) ? canton.trim() : CANTON_NO_INDICADO;
    if (!StringUtils.hasText(canton)) {
      LOG.warn("analisis-ia sin cantón producto={}: solo requisitos generales", code);
    }
    String ctx = contextoExpediente != null ? contextoExpediente : "";
    String prompt =
        promptRegistryService.resolvePrompt(
            promptKey, Map.of("canton", cantonResuelto, "contextoExpediente", ctx));
    return new Ejecucion(promptKey, analisis.procesarExpedienteCompleto(ocrMarcado, prompt));
  }

  public record Ejecucion(String promptKey, ExtraccionExpedienteCompleto extraccion) {}
}
