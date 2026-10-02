package com.lexia.api.modules.expedientes.coactivas.ia;

import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import com.lexia.api.modules.tenancy.TenantContext;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
  /** Por debajo de esto el PDF es escaneo: PDFBox no alcanza y hay que pasar por Azure. */
  private static final int MIN_TEXTO_PDF = 80;
  private static final String PROMPT_FALLBACK =
      "Eres un auditor legal de BanEcuador. Valida el documento del expediente coactivo.";

  private final CoactivaArchivoRepository archivos;
  private final CoactivaArchivoStorage storage;
  private final AzureOcrService ocr;
  private final AnalisisDocumentoService analisis;
  private final CoactivaPromptCatalogRepository catalog;
  private final CoactivaIaResultadoParser parser;
  private final boolean dummy;
  private final TransactionTemplate tx;

  public CoactivaValidacionIaWorker(
      CoactivaArchivoRepository archivos,
      CoactivaArchivoStorage storage,
      AzureOcrService ocr,
      AnalisisDocumentoService analisis,
      CoactivaPromptCatalogRepository catalog,
      CoactivaIaResultadoParser parser,
      PlatformTransactionManager txManager,
      @Value("${lexia.coactivas.ia.dummy:false}") boolean dummy) {
    this.archivos = archivos;
    this.storage = storage;
    this.ocr = ocr;
    this.analisis = analisis;
    this.catalog = catalog;
    this.parser = parser;
    this.dummy = dummy;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * Dos pasos, fuera de la transacción HTTP: (1) texto con PDFBox o Azure OCR, (2) ese texto +
   * prompt al LLM. El {@code @Transactional} en el hilo {@code @Async} no commiteaba y el archivo
   * se quedaba en ANALIZANDO: el front polleaba {@code /archivos} sin fin.
   */
  @Async
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
    LOG.info("Paso 1 OCR archivo={}", archivo.getId());
    String texto = extraerTexto(archivo);
    if (!StringUtils.hasText(texto)) {
      return CoactivaIaResultado.error("El documento no contiene texto legible.");
    }
    LOG.info("Paso 2 LLM archivo={} chars={}", archivo.getId(), texto.length());
    String system =
        catalog
            .resolver(archivo.getTipo(), archivo.getEtapaIa())
            .map(CoactivaPromptCatalog::getSystemPrompt)
            .orElse(PROMPT_FALLBACK);
    ValidacionDocumentoJson raw =
        analisis.validarDocumento(
            system + CoactivaIaResultadoParser.FORMATO_JSON, truncate(texto, MAX_CHARS));
    if (raw.esError()) {
      return CoactivaIaResultado.error(raw.error());
    }
    return parser.parse(raw.json());
  }

  private String extraerTexto(CoactivaArchivo archivo) {
    byte[] bytes = storage.read(archivo);
    String pdf = extraerPdf(bytes, archivo);
    if (pdf.length() >= MIN_TEXTO_PDF) {
      LOG.info("OCR archivo={} origen=pdf-texto chars={}", archivo.getId(), pdf.length());
      return pdf;
    }
    if (!ocr.isConfigured()) {
      return pdf;
    }
    try {
      String azure = ocr.extraerTexto(bytes, archivo.getMimeType());
      LOG.info(
          "OCR archivo={} origen=azure chars={}",
          archivo.getId(),
          azure == null ? 0 : azure.length());
      return azure == null ? "" : azure;
    } catch (Exception e) {
      LOG.warn("OCR Azure falló archivo={}: {}", archivo.getId(), e.getMessage());
      return pdf;
    }
  }

  private static String extraerPdf(byte[] bytes, CoactivaArchivo archivo) {
    if (bytes == null || bytes.length < 5 || !esPdf(archivo, bytes)) {
      return "";
    }
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      String texto = new PDFTextStripper().getText(doc);
      return texto == null ? "" : texto.trim();
    } catch (IOException e) {
      LOG.warn("PDFBox falló archivo={}: {}", archivo.getId(), e.getMessage());
      return "";
    }
  }

  private static boolean esPdf(CoactivaArchivo archivo, byte[] bytes) {
    String mime = archivo.getMimeType() == null ? "" : archivo.getMimeType().toLowerCase(Locale.ROOT);
    String nombre =
        archivo.getNombreOriginal() == null ? "" : archivo.getNombreOriginal().toLowerCase(Locale.ROOT);
    if (mime.contains("pdf") || nombre.endsWith(".pdf")) {
      return true;
    }
    return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
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
