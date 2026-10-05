package com.lexia.api.modules.expedientes.coactivas.ia;

import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaPdfTexto.TextoOcr;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** Worker asíncrono: OCR → prompt del catálogo → LLM JSON → estado del archivo. */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaValidacionIaWorker {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaValidacionIaWorker.class);
  private static final int MAX_CHARS = 80_000;
  private static final String PROMPT_FALLBACK =
      "Eres un auditor legal de BanEcuador. Valida el documento del expediente coactivo.";

  private final CoactivaArchivoRepository archivos;
  private final CoactivaArchivoStorage storage;
  private final CoactivaPdfTexto pdfTexto;
  private final AnalisisDocumentoService analisis;
  private final CoactivaPromptCatalogRepository catalog;
  private final CoactivaIaResultadoParser parser;
  private final boolean dummy;
  private final TransactionTemplate tx;

  public CoactivaValidacionIaWorker(
      CoactivaArchivoRepository archivos,
      CoactivaArchivoStorage storage,
      CoactivaPdfTexto pdfTexto,
      AnalisisDocumentoService analisis,
      CoactivaPromptCatalogRepository catalog,
      CoactivaIaResultadoParser parser,
      PlatformTransactionManager txManager,
      @Value("${lexia.coactivas.ia.dummy:false}") boolean dummy) {
    this.archivos = archivos;
    this.storage = storage;
    this.pdfTexto = pdfTexto;
    this.analisis = analisis;
    this.catalog = catalog;
    this.parser = parser;
    this.dummy = dummy;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * Dos pasos, fuera de la transacción HTTP: (1) Azure OCR del archivo completo, (2) ese texto +
   * prompt al LLM. El {@code @Transactional} en el hilo {@code @Async} no commiteaba y el archivo
   * se quedaba en ANALIZANDO: el front polleaba {@code /archivos} sin fin.
   */
  @Async("taskExecutor")
  public void analizarAsync(UUID tenantId, UUID archivoId) {
    if (TenantContext.getTenantId() == null) {
      TenantContext.setTenantId(tenantId);
    }
    CoactivaArchivo archivo = cargar(tenantId, archivoId);
    if (archivo == null || !archivo.analizando()) {
      LOG.warn("Validación IA sin archivo visible archivo={} tenant={}", archivoId, tenantId);
      return;
    }
    try {
      CoactivaIaResultado resultado = analizar(archivo);
      guardar(
          tenantId,
          archivoId,
          resultado.estado(),
          resultado.razonRechazo(),
          resultado.confianza(),
          parser.checklistJson(resultado.checklistCumplido()));
      LOG.info(
          "Validación IA archivo={} estado={} confianza={}",
          archivoId,
          resultado.estado(),
          resultado.confianza());
    } catch (Exception e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOG.warn("Fallo validación IA archivo={}: {}", archivoId, msg);
      guardar(tenantId, archivoId, CoactivaArchivo.IA_ERROR, truncate(msg, 600), null, null);
    }
  }

  private CoactivaArchivo cargar(UUID tenantId, UUID archivoId) {
    return tx.execute(
        status -> archivos.findByIdAndTenantIdAndDeletedAtIsNull(archivoId, tenantId).orElse(null));
  }

  private void guardar(
      UUID tenantId, UUID archivoId, String estado, String motivo, Integer confianza, String checklist) {
    tx.executeWithoutResult(
        status -> {
          CoactivaArchivo row =
              archivos.findByIdAndTenantIdAndDeletedAtIsNull(archivoId, tenantId).orElse(null);
          if (row == null || !row.analizando()) {
            return;
          }
          row.registrarResultadoIa(estado, motivo, confianza, checklist);
        });
  }

  private CoactivaIaResultado analizar(CoactivaArchivo archivo) {
    if (dummy || !analisis.isConfigured()) {
      LOG.info(
          "Validación IA dummy archivo={} llmConfigured={}",
          archivo.getId(),
          analisis.isConfigured());
      return dummyResultado();
    }
    LOG.info("Paso 1 OCR Azure archivo={}", archivo.getId());
    TextoOcr extraido =
        pdfTexto.extraer(storage.read(archivo), archivo.getMimeType(), archivo.getNombreOriginal());
    if (CoactivaPdfTexto.calidadInsuficiente(extraido)) {
      return CoactivaIaResultado.error(CoactivaPdfTexto.mensajeCalidad(extraido.paginas()));
    }
    if (!StringUtils.hasText(extraido.texto())) {
      return CoactivaIaResultado.error("El documento no contiene texto legible.");
    }
    LOG.info("Paso 2 LLM archivo={} chars={}", archivo.getId(), extraido.caracteres());
    String system =
        catalog
            .resolver(archivo.getTipo(), archivo.getEtapaIa())
            .map(CoactivaPromptCatalog::getSystemPrompt)
            .orElse(PROMPT_FALLBACK);
    ValidacionDocumentoJson raw =
        analisis.validarDocumento(
            system + CoactivaIaResultadoParser.FORMATO_JSON, truncate(extraido.texto(), MAX_CHARS));
    if (raw.esError()) {
      return CoactivaIaResultado.error(raw.error());
    }
    return parser.parse(raw.json());
  }

  private static CoactivaIaResultado dummyResultado() {
    if (ThreadLocalRandom.current().nextBoolean()) {
      return CoactivaIaResultado.aprobado(50, List.of("dummy"));
    }
    return CoactivaIaResultado.rechazado(
        50, "Prompt de prueba: rechazo aleatorio (LLM no configurado).", List.of());
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max);
  }
}
