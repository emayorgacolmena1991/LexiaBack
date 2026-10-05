package com.lexia.api.modules.expedientes.coactivas.ia;

import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaEvento;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaEventoRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipante;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipanteRepository;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnostico.Documento;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnosticoParser.Parse;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * PDF único del juicio: OCR por fojas, prompt de {@code coactiva_prompt_catalog} según la etapa,
 * diagnóstico JSON en {@code coactiva_analisis}.
 */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaExpedienteAnalisisWorker {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaExpedienteAnalisisWorker.class);
  private static final int MAX_CHARS = 120_000;
  private static final String TIPO_PROMPT = "EXPEDIENTE_UNIFICADO";
  private static final String PROMPT_FALLBACK =
      "Eres un auditor legal de BanEcuador. Diagnostica el expediente coactivo completo.";

  private final CoactivaArchivoRepository archivos;
  private final CoactivaArchivoStorage storage;
  private final CoactivaExpedienteRepository expedientes;
  private final CoactivaParticipanteRepository participantes;
  private final CoactivaAnalisisRepository analisisRepo;
  private final CoactivaEventoRepository eventos;
  private final CoactivaPdfTexto pdfTexto;
  private final AnalisisDocumentoService analisis;
  private final CoactivaPromptCatalogRepository catalog;
  private final CoactivaDiagnosticoParser parser;
  private final boolean dummy;
  private final TransactionTemplate tx;

  public CoactivaExpedienteAnalisisWorker(
      CoactivaArchivoRepository archivos,
      CoactivaArchivoStorage storage,
      CoactivaExpedienteRepository expedientes,
      CoactivaParticipanteRepository participantes,
      CoactivaAnalisisRepository analisisRepo,
      CoactivaEventoRepository eventos,
      CoactivaPdfTexto pdfTexto,
      AnalisisDocumentoService analisis,
      CoactivaPromptCatalogRepository catalog,
      CoactivaDiagnosticoParser parser,
      PlatformTransactionManager txManager,
      @Value("${lexia.coactivas.ia.dummy:false}") boolean dummy) {
    this.archivos = archivos;
    this.storage = storage;
    this.expedientes = expedientes;
    this.participantes = participantes;
    this.analisisRepo = analisisRepo;
    this.eventos = eventos;
    this.pdfTexto = pdfTexto;
    this.analisis = analisis;
    this.catalog = catalog;
    this.parser = parser;
    this.dummy = dummy;
    this.tx = new TransactionTemplate(txManager);
  }

  @Async
  public void analizarAsync(UUID tenantId, UUID archivoId) {
    if (TenantContext.getTenantId() == null) {
      TenantContext.setTenantId(tenantId);
    }
    CoactivaArchivo archivo = cargarArchivo(tenantId, archivoId);
    if (archivo == null || !archivo.analizando() || archivo.getExpedienteId() == null) {
      LOG.warn("Diagnóstico sin archivo en análisis archivo={} tenant={}", archivoId, tenantId);
      return;
    }
    Contexto ctx = cargarContexto(tenantId, archivo.getExpedienteId());
    try {
      Resultado resultado = diagnosticar(archivo, ctx);
      guardar(tenantId, archivoId, resultado);
      LOG.info(
          "Diagnóstico expediente={} archivo={} porcentaje={}",
          archivo.getExpedienteId(),
          archivoId,
          resultado.diagnostico() == null ? null : resultado.diagnostico().porcentajeCompletitud());
    } catch (Exception e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOG.warn("Fallo diagnóstico archivo={}: {}", archivoId, msg);
      guardar(tenantId, archivoId, Resultado.error(ctx.etapa(), null, msg));
    }
  }

  private Resultado diagnosticar(CoactivaArchivo archivo, Contexto ctx) {
    CoactivaPromptCatalog prompt =
        catalog.resolver(TIPO_PROMPT, ctx.etapa()).orElse(null);
    String system = contextualizar(prompt == null ? PROMPT_FALLBACK : prompt.getSystemPrompt(), ctx);
    Long promptId = prompt == null ? null : prompt.getId();
    if (dummy || !analisis.isConfigured()) {
      LOG.info("Diagnóstico dummy archivo={} llmConfigured={}", archivo.getId(), analisis.isConfigured());
      Parse parsed = parser.parse(dummyJson(ctx));
      return parsed.ok()
          ? Resultado.ok(ctx.etapa(), promptId, parsed.diagnostico())
          : Resultado.error(ctx.etapa(), promptId, parsed.error());
    }
    LOG.info("Paso 1 OCR expediente archivo={}", archivo.getId());
    String texto = pdfTexto.extraer(storage.read(archivo), archivo.getMimeType(), archivo.getNombreOriginal());
    if (!StringUtils.hasText(texto)) {
      return Resultado.error(ctx.etapa(), promptId, "El PDF no contiene texto legible.");
    }
    LOG.info("Paso 2 LLM archivo={} chars={}", archivo.getId(), texto.length());
    ValidacionDocumentoJson raw =
        analisis.diagnosticarCoactiva(system + CoactivaDiagnosticoParser.FORMATO_JSON, truncate(texto, MAX_CHARS));
    if (raw.esError()) {
      return Resultado.error(ctx.etapa(), promptId, raw.error());
    }
    Parse parsed = parser.parse(raw.json());
    if (!parsed.ok()) {
      return Resultado.error(ctx.etapa(), promptId, parsed.error());
    }
    return Resultado.ok(ctx.etapa(), promptId, parsed.diagnostico());
  }

  private void guardar(UUID tenantId, UUID archivoId, Resultado resultado) {
    tx.executeWithoutResult(
        status -> {
          CoactivaArchivo archivo =
              archivos.findByIdAndTenantIdAndDeletedAtIsNull(archivoId, tenantId).orElse(null);
          if (archivo == null || !archivo.analizando() || archivo.getExpedienteId() == null) {
            return;
          }
          CoactivaExpediente expediente =
              expedientes
                  .findByIdAndTenantIdAndDeletedAtIsNull(archivo.getExpedienteId(), tenantId)
                  .orElse(null);
          boolean vigente =
              expediente != null && archivoId.equals(expediente.getAnalisisArchivoId());
          if (resultado.error() != null) {
            analisisRepo.save(
                CoactivaAnalisis.error(
                    tenantId,
                    archivo.getExpedienteId(),
                    archivoId,
                    resultado.promptId(),
                    resultado.etapa(),
                    resultado.error()));
            archivo.registrarResultadoIa(CoactivaArchivo.IA_ERROR, truncate(resultado.error(), 600), null, null);
            if (vigente) {
              expediente.marcarErrorAnalisis();
            }
            return;
          }
          CoactivaDiagnostico diag = resultado.diagnostico();
          analisisRepo.save(
              CoactivaAnalisis.ok(
                  tenantId, archivo.getExpedienteId(), archivoId, resultado.promptId(), resultado.etapa(), diag));
          archivo.registrarResultadoIa(
              CoactivaArchivo.IA_APROBADO, null, diag.porcentajeCompletitud(), checklist(diag.documentos()));
          if (vigente) {
            expediente.marcarAnalizado();
            if (diag.etapaNormalizada() != null) {
              expediente.setEtapaSugeridaIa(diag.etapaNormalizada());
            }
            eventos.save(
                CoactivaEvento.create(
                    tenantId,
                    expediente.getId(),
                    "EXPEDIENTE_ANALIZADO",
                    "Diagnóstico del expediente",
                    diag.siguienteAccion(),
                    null,
                    archivoId));
          }
        });
  }

  private CoactivaArchivo cargarArchivo(UUID tenantId, UUID archivoId) {
    return tx.execute(
        status -> archivos.findByIdAndTenantIdAndDeletedAtIsNull(archivoId, tenantId).orElse(null));
  }

  private Contexto cargarContexto(UUID tenantId, UUID expedienteId) {
    return tx.execute(
        status -> {
          CoactivaExpediente expediente =
              expedientes.findByIdAndTenantIdAndDeletedAtIsNull(expedienteId, tenantId).orElse(null);
          if (expediente == null) {
            return new Contexto("*", "", "", "");
          }
          String etapa = expediente.getEtapaVerificada();
          if (!StringUtils.hasText(etapa)) {
            etapa = expediente.getEtapaReportada();
          }
          String deudor =
              participantes
                  .findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByOrdenAsc(tenantId, expedienteId)
                  .stream()
                  .filter(p -> CoactivaParticipante.DEUDOR.equals(p.getRol()))
                  .map(CoactivaParticipante::getNombreCompleto)
                  .findFirst()
                  .orElse("");
          return new Contexto(
              StringUtils.hasText(etapa) ? etapa : "*",
              nulo(expediente.getNroJuicio()),
              nulo(expediente.getNroOperacion()),
              deudor);
        });
  }

  private static String contextualizar(String prompt, Contexto ctx) {
    String etapa =
        "*".equals(ctx.etapa())
            ? "no indicada"
            : CoactivaEtapa.parse(ctx.etapa()).map(CoactivaEtapa::label).orElse(ctx.etapa());
    return prompt
        .replace("{{etapa}}", etapa)
        .replace("{{nro_juicio}}", ctx.juicio())
        .replace("{{nro_operacion}}", ctx.operacion())
        .replace("{{deudor}}", StringUtils.hasText(ctx.deudor()) ? ctx.deudor() : "no indicado");
  }

  private static String checklist(List<Documento> documentos) {
    StringBuilder sb = new StringBuilder("[");
    boolean primero = true;
    for (Documento doc : documentos) {
      if (!doc.presente() || doc.tipo() == null) {
        continue;
      }
      if (!primero) {
        sb.append(',');
      }
      primero = false;
      sb.append('"').append(doc.tipo().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return sb.append(']').toString();
  }

  private static String dummyJson(Contexto ctx) {
    return """
        {"porcentaje_completitud": 40,
         "etapa_procesal_detectada": "%s",
         "documentos_identificados": [
           {"tipo": "PAGARE", "foja_inicio": 1, "foja_fin": 3, "presente": true},
           {"tipo": "LIQUIDACION_ACTUALIZADA", "presente": false}
         ],
         "alertas_inconsistencias": ["Diagnóstico de prueba: LLM no configurado."],
         "datos_extraidos": {"juicio": "%s", "operacion": "%s", "deudor": "%s", "monto_mora": null},
         "siguiente_accion_sugerida": "Revisar el PDF cuando el LLM esté configurado."}
        """
        .formatted(ctx.etapa(), escape(ctx.juicio()), escape(ctx.operacion()), escape(ctx.deudor()));
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String nulo(String s) {
    return s == null ? "" : s;
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max);
  }

  private record Contexto(String etapa, String juicio, String operacion, String deudor) {}

  private record Resultado(String etapa, Long promptId, CoactivaDiagnostico diagnostico, String error) {
    static Resultado ok(String etapa, Long promptId, CoactivaDiagnostico diagnostico) {
      return new Resultado(etapa, promptId, diagnostico, null);
    }

    static Resultado error(String etapa, Long promptId, String error) {
      return new Resultado(etapa, promptId, null, error);
    }
  }
}
