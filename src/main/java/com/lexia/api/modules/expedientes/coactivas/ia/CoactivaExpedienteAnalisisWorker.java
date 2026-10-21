package com.lexia.api.modules.expedientes.coactivas.ia;

import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaEvento;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaEventoRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaTareaSugerida;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaTareaSugeridaRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipante;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipanteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnostico.Documento;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnosticoParser.Parse;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.BatchEventPublisherService;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaJobQueue;
import com.lexia.api.modules.ia.gemini.GeminiJsonSanitizer;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import com.lexia.api.modules.tenancy.RlsConnectionProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * PDF único del juicio: Azure OCR del archivo completo, prompt de {@code coactiva_prompt_catalog}
 * según la etapa, diagnóstico JSON en {@code coactiva_analisis}.
 */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaExpedienteAnalisisWorker {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaExpedienteAnalisisWorker.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  /** ponytail: foja más larga que esto se parte en el último salto de línea. Subir si una foja no cabe. */
  private static final int CHUNK_CHARS = 80_000;
  private static final int MAX_HISTORIAL = 12_000;
  private static final int MAX_ALERTAS = 40;
  /** ponytail: tope de ventanas; si un juicio nombra más depositarios, subir MAX_FRAGMENTOS. */
  private static final int MAX_FRAGMENTOS = 12;
  /** ponytail: 800 centrados en el hit. Subir VENTANA si la cédula queda a más de 400 caracteres. */
  private static final int VENTANA = 800;
  private static final Pattern DEPOSITARIO =
      Pattern.compile("depositario", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final String ENCABEZADO_FRAGMENTOS =
      "\n\n--- DATOS DEL DEPOSITARIO ENCONTRADOS EN EL TEXTO ---\n";
  private static final String PROMPT_DEPOSITARIO =
      "Eres un extractor de datos. Del siguiente texto, extrae el nombre completo, cédula y fecha de designación del Depositario Judicial. Devuelve SOLO un JSON con las claves nombre_depositario, cedula_depositario y fecha_auto_designacion.";
  private static final String HISTORIAL =
      """
      === HISTORIAL DE DIAGNÓSTICO PREVIO ===
      %s
      === NUEVA PIEZA PROCESAL ===

      INSTRUCCIÓN: La pieza nueva puede cambiar la etapa, cerrar observaciones ya resueltas o agregar tareas. Actualiza etapa_sugerida, resuelve observaciones que el documento cierre y suma acciones nuevas. No elimines hitos confirmados salvo revocatoria explícita.
      """;
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
  private final CoactivaTareaSugeridaRepository tareas;
  private final IngestaJobQueue jobs;
  private final BatchEventPublisherService sse;
  private final CoactivaValidacionIaWorker validacion;
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
      CoactivaTareaSugeridaRepository tareas,
      IngestaJobQueue jobs,
      BatchEventPublisherService sse,
      CoactivaValidacionIaWorker validacion,
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
    this.tareas = tareas;
    this.jobs = jobs;
    this.sse = sse;
    this.validacion = validacion;
    this.dummy = dummy;
    this.tx = new TransactionTemplate(txManager);
  }

  /** Inserta en {@code app.jobs} dentro de la transacción del llamador. Lo corre {@code IngestaJobWorker}. */
  public void encolar(UUID tenantId, UUID expedienteId, UUID archivoId) {
    jobs.encolar(tenantId, "COACTIVA_DIAGNOSTICO", payload(expedienteId, archivoId));
    anunciar(expedienteId);
  }

  public void encolarAnexo(UUID tenantId, UUID expedienteId, UUID archivoId) {
    jobs.encolar(tenantId, "COACTIVA_VALIDACION_ANEXO", payload(expedienteId, archivoId));
    anunciar(expedienteId);
  }

  private void anunciar(UUID expedienteId) {
    LOG.info("[COACTIVA] Job encolado en app.jobs para expediente {}", expedienteId);
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      avisar(expedienteId, "ANALYSIS_STARTED");
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            avisar(expedienteId, "ANALYSIS_STARTED");
          }
        });
  }

  private static String payload(UUID expedienteId, UUID archivoId) {
    return "{\"expedienteId\":\"" + expedienteId + "\",\"archivoId\":\"" + archivoId + "\"}";
  }

  /**
   * Las respuestas inválidas de la IA se guardan como error. Una excepción (OCR Azure, disco, BD)
   * sale al worker de {@code app.jobs} para reintento; {@link #abandonar} cierra el análisis al
   * agotar los intentos.
   */
  public void ejecutar(UUID tenantId, UUID archivoId) {
    RlsConnectionProvider.setTenantId(tenantId);
    CoactivaArchivo archivo = cargarArchivo(tenantId, archivoId);
    if (archivo != null && !CoactivaArchivo.esExpedienteUnificado(archivo.getTipo())) {
      LOG.info(
          "Archivo tipo={} deriva a CoactivaValidacionIaWorker archivo={}",
          archivo.getTipo(),
          archivoId);
      validacion.ejecutar(tenantId, archivoId);
      avisar(archivo.getExpedienteId(), "ANALYSIS_COMPLETED");
      return;
    }
    if (archivo == null || !archivo.analizando() || archivo.getExpedienteId() == null) {
      LOG.warn("Diagnóstico sin archivo en análisis archivo={} tenant={}", archivoId, tenantId);
      return;
    }
    UUID expedienteId = archivo.getExpedienteId();
    avisar(expedienteId, "ANALYSIS_STARTED");
    Contexto ctx = cargarContexto(tenantId, expedienteId);
    Resultado resultado = diagnosticar(archivo, ctx, cargarPrevio(tenantId, expedienteId));
    guardar(tenantId, archivoId, resultado);
    if (resultado.error() != null) {
      fallo(expedienteId, resultado.error());
    } else {
      avisar(expedienteId, "ANALYSIS_COMPLETED");
    }
    LOG.info(
        "Diagnóstico expediente={} archivo={} porcentaje={}",
        expedienteId,
        archivoId,
        resultado.diagnostico() == null ? null : resultado.diagnostico().porcentajeCompletitud());
  }

  /**
   * El job agotó sus reintentos en {@code app.jobs}: sin esto el archivo y el expediente quedan en
   * ANALIZANDO para siempre y {@code reanalizar} responde 409.
   */
  public void abandonar(UUID tenantId, UUID archivoId, String error) {
    RlsConnectionProvider.setTenantId(tenantId);
    String msg = truncate(error == null ? "El análisis no terminó." : error, 600);
    UUID expedienteId =
        tx.execute(
            status -> {
              CoactivaArchivo archivo =
                  archivos.findByIdAndTenantIdAndDeletedAtIsNull(archivoId, tenantId).orElse(null);
              if (archivo == null || !archivo.analizando()) {
                return null;
              }
              archivo.registrarResultadoIa(CoactivaArchivo.IA_ERROR, msg, null, null);
              if (archivo.getExpedienteId() != null && CoactivaArchivo.esExpedienteUnificado(archivo.getTipo())) {
                analisisRepo.save(
                    CoactivaAnalisis.error(
                        tenantId,
                        archivo.getExpedienteId(),
                        archivoId,
                        null,
                        archivo.getEtapaIa() == null ? "*" : archivo.getEtapaIa(),
                        msg));
                expedientes
                    .bloquear(archivo.getExpedienteId(), tenantId)
                    .filter(e -> archivoId.equals(e.getAnalisisArchivoId()))
                    .ifPresent(CoactivaExpediente::marcarErrorAnalisis);
              }
              return archivo.getExpedienteId();
            });
    fallo(expedienteId, msg);
  }

  private void fallo(UUID expedienteId, String error) {
    if (expedienteId == null) {
      return;
    }
    sse.publicar(
        expedienteId,
        "ANALYSIS_FAILED",
        Map.of("expedienteId", expedienteId.toString(), "error", error == null ? "" : error));
  }

  private Resultado diagnosticar(CoactivaArchivo archivo, Contexto ctx, Previo previo) {
    LOG.info("diag start archivo={} dummy={} configured={} etapa={}", archivo.getId(), dummy, analisis.isConfigured(), ctx.etapa());
    CoactivaPromptCatalog prompt =
        catalog.resolver(TIPO_PROMPT, ctx.etapa()).orElse(null);
    String system =
        contextualizar(prompt == null ? PROMPT_FALLBACK : prompt.getSystemPrompt(), ctx)
            + variablesDeEtapa(ctx.etapa());
    Long promptId = prompt == null ? null : prompt.getId();
    if (dummy) {
      LOG.info("Diagnóstico dummy archivo={}", archivo.getId());
      Parse parsed = parser.parse(DUMMY_JSON);
      return parsed.ok()
          ? Resultado.ok(ctx.etapa(), promptId, parsed.diagnostico())
          : Resultado.error(ctx.etapa(), promptId, parsed.error());
    }
    if (!analisis.isConfigured()) {
      LOG.warn("Diagnóstico sin proveedor IA archivo={}", archivo.getId());
      return Resultado.error(ctx.etapa(), promptId, "No hay un proveedor de IA configurado para diagnosticar.");
    }
    LOG.info("Paso 1 OCR Azure expediente archivo={}", archivo.getId());
    CoactivaPdfTexto.TextoOcr extraido =
        pdfTexto.extraer(
            archivo.getTenantId(),
            archivo.getSha256(),
            () -> storage.read(archivo),
            archivo.getMimeType(),
            archivo.getNombreOriginal());
    if (CoactivaPdfTexto.calidadInsuficiente(extraido)) {
      LOG.warn("Diagnóstico OCR calidad insuficiente archivo={}", archivo.getId());
      return Resultado.calidad(ctx.etapa(), promptId, CoactivaPdfTexto.mensajeCalidad(extraido.paginas()));
    }
    if (!StringUtils.hasText(extraido.texto())) {
      LOG.warn("Diagnóstico OCR sin texto archivo={}", archivo.getId());
      return Resultado.error(ctx.etapa(), promptId, "El PDF no contiene texto legible.");
    }
    List<String> bloques = partirOcr(extraido.texto());
    String reglas =
        system + CoactivaDiagnosticoParser.REGLAS_ETAPA + CoactivaDiagnosticoParser.FORMATO_JSON;
    LOG.info(
        "Paso 2 LLM archivo={} chars={} bloques={}",
        archivo.getId(),
        extraido.caracteres(),
        bloques.size());
    ObjectNode unido = null;
    for (int i = 0; i < bloques.size(); i++) {
      String texto = i == 0 ? textoConHistorial(bloques.get(i), previo) : bloques.get(i);
      LOG.info(
          "Paso 2 LLM bloque={}/{} archivo={} chars={}",
          i + 1,
          bloques.size(),
          archivo.getId(),
          texto.length());
      ValidacionDocumentoJson raw = analisis.diagnosticarCoactiva(reglas, texto);
      if (raw.esError()) {
        LOG.warn(
            "Paso 2 LLM error archivo={} bloque={}/{}: {}",
            archivo.getId(),
            i + 1,
            bloques.size(),
            truncate(raw.error(), 300));
        return Resultado.error(ctx.etapa(), promptId, raw.error());
      }
      LOG.info(
          "Claude raw archivo={} bloque={}/{} json={}",
          archivo.getId(),
          i + 1,
          bloques.size(),
          raw.json());
      Parse parsed = parser.parse(raw.json());
      if (!parsed.ok()) {
        LOG.warn(
            "Diagnóstico JSON inválido archivo={} bloque={}/{}: {}",
            archivo.getId(),
            i + 1,
            bloques.size(),
            truncate(parsed.error(), 300));
        return Resultado.error(ctx.etapa(), promptId, parsed.error());
      }
      unido = unirDiagnostico(unido, parsed.diagnostico().json());
    }
    if (unido == null) {
      return Resultado.error(ctx.etapa(), promptId, "La IA devolvió una respuesta vacía.");
    }
    unido = segundaPasadaDepositario(archivo.getId(), unido, extraido.texto());
    String jsonUnido;
    try {
      jsonUnido = JSON.writeValueAsString(unido);
    } catch (Exception e) {
      return Resultado.error(ctx.etapa(), promptId, "No se pudo unir el diagnóstico por bloques.");
    }
    Parse parsed = parser.parse(jsonUnido);
    if (!parsed.ok()) {
      LOG.warn(
          "Diagnóstico JSON unificado inválido archivo={}: {}",
          archivo.getId(),
          truncate(parsed.error(), 300));
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
              expedientes.bloquear(archivo.getExpedienteId(), tenantId).orElse(null);
          Previo previo = leerPrevio(tenantId, archivo.getExpedienteId());
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
              if (resultado.calidad()) {
                expediente.marcarErrorCalidad();
              } else {
                expediente.marcarErrorAnalisis();
              }
            }
            return;
          }
          CoactivaDiagnostico diag = resultado.diagnostico();
          // Un PDF reemplazado mientras corría solo suma sus piezas: la etapa sugerida, la
          // justificación y la evidencia siguen siendo las del PDF vigente.
          CoactivaDiagnosticoMerge.Fusion fusion =
              vigente
                  ? CoactivaDiagnosticoMerge.fusionar(
                      previo == null ? null : previo.json(),
                      previo == null ? null : previo.archivoId(),
                      previo == null ? null : previo.fecha(),
                      archivoId,
                      archivo.getNombreOriginal(),
                      tipoPieza(diag),
                      diag)
                  : CoactivaDiagnosticoMerge.pieza(
                      previo == null ? null : previo.json(),
                      previo == null ? null : previo.archivoId(),
                      previo == null ? null : previo.fecha(),
                      archivoId,
                      archivo.getNombreOriginal(),
                      archivo.getTipo(),
                      CoactivaArchivo.IA_APROBADO,
                      checklist(diag.documentos()));
          CoactivaAnalisis guardado =
              analisisRepo.save(
                  CoactivaAnalisis.consolidado(
                      tenantId,
                      archivo.getExpedienteId(),
                      archivoId,
                      resultado.promptId(),
                      resultado.etapa(),
                      fusion.json(),
                      fusion.porcentaje(),
                      vigente && diag.etapaNormalizada() != null ? diag.etapaNormalizada() : fusion.etapa(),
                      previo == null ? null : previo.id()));
          if (vigente) {
            poblarTareas(tenantId, archivo.getExpedienteId(), guardado.getId(), fusion.json());
          }
          archivo.registrarResultadoIa(
              CoactivaArchivo.IA_APROBADO, null, diag.porcentajeCompletitud(), checklist(diag.documentos()));
          if (vigente) {
            expediente.marcarAnalizado();
            expediente.setEtapaSugeridaIa(diag.etapaNormalizada());
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

  private void poblarTareas(UUID tenantId, UUID expedienteId, UUID analisisId, String json) {
    tareas.deleteByTenantIdAndExpedienteIdAndEstado(tenantId, expedienteId, CoactivaTareaSugerida.SUGERIDA);
    int creadas = 0;
    for (var accion : parser.tentativaDe(json).acciones()) {
      creadas++;
      tareas.save(
          CoactivaTareaSugerida.nueva(
              tenantId, expedienteId, analisisId, accion.titulo(), accion.descripcion(), accion.prioridad()));
    }
    if (creadas > 0) {
      avisar(expedienteId, "TASK_SUGGESTED");
    }
  }

  private void avisar(UUID expedienteId, String evento) {
    if (expedienteId == null) {
      return;
    }
    sse.publicar(expedienteId, evento, Map.of("expedienteId", expedienteId.toString()));
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

  private String variablesDeEtapa(String etapa) {
    if (etapa == null || etapa.isBlank()) {
      return "";
    }
    List<CoactivaPromptCatalog> filas =
        catalog.findByTipoDocumentoAndEtapaAndActivoTrue("PLANTILLA", etapa);
    if (filas == null || filas.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(
        "\n\nPlantillas de esta etapa. En datos_extraidos incluye TODAS las variables del schema. Valor literal del OCR si consta; null si no consta. No omitas claves:\n");
    for (CoactivaPromptCatalog fila : filas) {
      if (fila.getVariablesRequeridas() == null || fila.getVariablesRequeridas().isBlank()) {
        continue;
      }
      sb.append("- ")
          .append(fila.getPlantillaArchivo())
          .append(": ")
          .append(CoactivaPlantillaDataMapper.textoExtraccion(fila.getVariablesRequeridas()))
          .append('\n');
    }
    return sb.toString();
  }

  private Previo cargarPrevio(UUID tenantId, UUID expedienteId) {
    return tx.execute(status -> leerPrevio(tenantId, expedienteId));
  }

  private Previo leerPrevio(UUID tenantId, UUID expedienteId) {
    return analisisRepo
        .findFirstByTenantIdAndExpedienteIdAndEstadoOrderByCreatedAtDesc(
            tenantId, expedienteId, CoactivaAnalisis.ANALIZADO)
        .map(a -> new Previo(a.getId(), a.getArchivoId(), a.getResultado(), a.getCreatedAt()))
        .orElse(null);
  }

  private ObjectNode segundaPasadaDepositario(UUID archivoId, ObjectNode unido, String ocr) {
    if (!vacio(unido.path("datos_extraidos").get("nombre_depositario"))) {
      return unido;
    }
    String fragmentos = fragmentosClave(ocr);
    if (!StringUtils.hasText(fragmentos)) {
      return unido;
    }
    LOG.info("Paso 3 depositario archivo={} chars={}", archivoId, fragmentos.length());
    ValidacionDocumentoJson raw = analisis.diagnosticarCoactiva(PROMPT_DEPOSITARIO, fragmentos);
    if (raw.esError()) {
      LOG.warn("Paso 3 depositario error archivo={}: {}", archivoId, truncate(raw.error(), 300));
      return unido;
    }
    String solo = soloDepositario(raw.json());
    if (solo == null) {
      return unido;
    }
    LOG.info("Paso 3 depositario archivo={} json={}", archivoId, solo);
    return unirDiagnostico(unido, solo);
  }

  /** Solo las tres claves, dentro de datos_extraidos, para que unirDiagnostico no mezcle otra etapa. */
  private static String soloDepositario(String raw) {
    JsonNode nodo;
    try {
      nodo = JSON.readTree(GeminiJsonSanitizer.limpiar(raw));
    } catch (Exception e) {
      return null;
    }
    if (nodo == null || !nodo.isObject()) {
      return null;
    }
    JsonNode anidado = nodo.get("datos_extraidos");
    ObjectNode datos = JSON.createObjectNode();
    for (String clave : List.of("nombre_depositario", "cedula_depositario", "fecha_auto_designacion")) {
      JsonNode valor = anidado != null && anidado.isObject() ? anidado.get(clave) : null;
      if (vacio(valor)) {
        valor = nodo.get(clave);
      }
      if (!vacio(valor)) {
        datos.set(clave, valor.deepCopy());
      }
    }
    if (datos.isEmpty()) {
      return null;
    }
    ObjectNode root = JSON.createObjectNode();
    root.set("datos_extraidos", datos);
    try {
      return JSON.writeValueAsString(root);
    } catch (Exception e) {
      return null;
    }
  }

  private static String fragmentosClave(String ocr) {
    if (!StringUtils.hasText(ocr)) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    recolectar(ocr, DEPOSITARIO, sb, 0);
    return sb.toString();
  }

  private static int recolectar(String ocr, Pattern clave, StringBuilder sb, int n) {
    if (n >= MAX_FRAGMENTOS) {
      return n;
    }
    for (String raw : ocr.split("\\R\\s*\\R")) {
      if (n >= MAX_FRAGMENTOS) {
        break;
      }
      String p = raw.trim();
      if (p.isEmpty() || !clave.matcher(p).find()) {
        continue;
      }
      if (p.length() <= VENTANA) {
        sb.append(n == 0 ? ENCABEZADO_FRAGMENTOS : "\n\n").append(p);
        n++;
        continue;
      }
      Matcher m = clave.matcher(p);
      int cubierto = -1;
      while (n < MAX_FRAGMENTOS && m.find()) {
        if (m.start() < cubierto) {
          continue;
        }
        int start = Math.max(0, m.start() - VENTANA / 2);
        int end = Math.min(p.length(), start + VENTANA);
        start = Math.max(0, end - VENTANA);
        sb.append(n == 0 ? ENCABEZADO_FRAGMENTOS : "\n\n").append(p, start, end);
        cubierto = end;
        n++;
      }
    }
    return n;
  }

  private static String textoConHistorial(String ocr, Previo previo) {
    if (previo == null || previo.json() == null || previo.json().isBlank() || "{}".equals(previo.json())) {
      return ocr;
    }
    return HISTORIAL.formatted(truncate(previo.json(), MAX_HISTORIAL)) + "\n" + ocr;
  }

  static List<String> partirOcr(String ocr) {
    if (ocr == null || ocr.isBlank()) {
      return List.of();
    }
    if (ocr.length() <= CHUNK_CHARS) {
      return List.of(ocr);
    }
    List<String> partes = new ArrayList<>();
    int i = 0;
    while (i < ocr.length()) {
      int fin = Math.min(ocr.length(), i + CHUNK_CHARS);
      if (fin < ocr.length()) {
        int corte = ultimoCorte(ocr, i, fin);
        if (corte > i) {
          fin = corte;
        }
      }
      String parte = ocr.substring(i, fin).strip();
      if (!parte.isEmpty()) {
        partes.add(parte);
      }
      if (fin <= i) {
        fin = Math.min(ocr.length(), i + CHUNK_CHARS);
      }
      i = fin;
    }
    return partes.isEmpty() ? List.of(ocr) : partes;
  }

  /** Última foja ({@code --- FOJA n ---}) o salto de página que cabe; si no hay, el último salto de línea. */
  private static int ultimoCorte(String ocr, int desde, int hasta) {
    int foja = ocr.lastIndexOf("\n--- FOJA ", hasta);
    if (foja <= desde) {
      foja = ocr.lastIndexOf("--- FOJA ", hasta);
    }
    if (foja > desde) {
      return foja;
    }
    int pagina = ocr.lastIndexOf('\f', hasta - 1);
    if (pagina > desde) {
      return pagina;
    }
    int nl = ocr.lastIndexOf('\n', hasta - 1);
    return nl > desde ? nl : hasta;
  }

  /**
   * Primer valor útil gana. Un null o un hueco del bloque siguiente no pisa lo ya hallado;
   * una clave que solo aparece después se agrega.
   */
  static ObjectNode unirDiagnostico(ObjectNode base, String json) {
    JsonNode nodo;
    try {
      nodo = JSON.readTree(json);
    } catch (Exception e) {
      return base;
    }
    if (nodo == null || !nodo.isObject()) {
      return base;
    }
    if (base == null) {
      return (ObjectNode) nodo.deepCopy();
    }
    ObjectNode nuevo = (ObjectNode) nodo;
    llenarDatos(objeto(base, "datos_extraidos"), nuevo.get("datos_extraidos"));
    base.set(
        "documentos_identificados",
        unirDocumentos(base.get("documentos_identificados"), nuevo.get("documentos_identificados")));
    base.set(
        "alertas_inconsistencias",
        unirTextos(base.get("alertas_inconsistencias"), nuevo.get("alertas_inconsistencias"), MAX_ALERTAS));
    base.set(
        "acciones_sugeridas",
        unirAcciones(base.get("acciones_sugeridas"), nuevo.get("acciones_sugeridas")));
    if (etapaMasAvanzada(codigoEtapa(nuevo), codigoEtapa(base))) {
      JsonNode sugerida = nuevo.get("etapa_sugerida");
      if (sugerida != null && sugerida.isObject()) {
        base.set("etapa_sugerida", sugerida.deepCopy());
      }
      String codigo = codigoEtapa(nuevo);
      if (codigo != null) {
        base.put("etapa_procesal_detectada", codigo);
      }
      JsonNode suf = nuevo.get("suficiencia_documental");
      if (suf != null && suf.isObject()) {
        base.set("suficiencia_documental", suf.deepCopy());
      }
    }
    base.put(
        "porcentaje_completitud",
        Math.max(base.path("porcentaje_completitud").asInt(0), nuevo.path("porcentaje_completitud").asInt(0)));
    if (vacio(base.get("siguiente_accion_sugerida")) && !vacio(nuevo.get("siguiente_accion_sugerida"))) {
      base.set("siguiente_accion_sugerida", nuevo.get("siguiente_accion_sugerida").deepCopy());
    }
    return base;
  }

  private static void llenarDatos(ObjectNode dest, JsonNode src) {
    if (src == null || !src.isObject()) {
      return;
    }
    src.fields()
        .forEachRemaining(
            e -> {
              JsonNode valor = e.getValue();
              if (vacio(valor)) {
                return;
              }
              JsonNode previo = dest.get(e.getKey());
              if (vacio(previo)) {
                dest.set(e.getKey(), valor.deepCopy());
              } else if (previo.isObject() && valor.isObject()) {
                llenarDatos((ObjectNode) previo, valor);
              } else if (previo.isArray() && valor.isArray()) {
                dest.set(e.getKey(), unirArrays(previo, valor));
              }
            });
  }

  private static ObjectNode objeto(ObjectNode base, String campo) {
    JsonNode n = base.get(campo);
    if (n instanceof ObjectNode obj) {
      return obj;
    }
    ObjectNode creado = JSON.createObjectNode();
    base.set(campo, creado);
    return creado;
  }

  private static ArrayNode unirDocumentos(JsonNode a, JsonNode b) {
    Map<String, ObjectNode> porTipo = new LinkedHashMap<>();
    agregarDocs(porTipo, a);
    agregarDocs(porTipo, b);
    ArrayNode arr = JSON.createArrayNode();
    porTipo.values().forEach(arr::add);
    return arr;
  }

  private static void agregarDocs(Map<String, ObjectNode> porTipo, JsonNode arr) {
    if (arr == null || !arr.isArray()) {
      return;
    }
    for (JsonNode item : arr) {
      if (!item.isObject()) {
        continue;
      }
      String tipo = item.path("tipo").asText("").trim();
      if (tipo.isEmpty()) {
        continue;
      }
      String key = tipo.toUpperCase(Locale.ROOT);
      ObjectNode prev = porTipo.get(key);
      if (prev == null || (item.path("presente").asBoolean(false) && !prev.path("presente").asBoolean(false))) {
        porTipo.put(key, ((ObjectNode) item).deepCopy());
        continue;
      }
      if (item.path("presente").asBoolean(false)) {
        if (fojaVacia(prev.get("foja_inicio"))) {
          copiar(prev, (ObjectNode) item, "foja_inicio");
        }
        if (fojaVacia(prev.get("foja_fin"))) {
          copiar(prev, (ObjectNode) item, "foja_fin");
        }
      }
      prev.put("presente", prev.path("presente").asBoolean(false) || item.path("presente").asBoolean(false));
    }
  }

  private static void copiar(ObjectNode dest, ObjectNode src, String campo) {
    JsonNode n = src.get(campo);
    if (n != null && !n.isNull()) {
      dest.set(campo, n.deepCopy());
    }
  }

  private static boolean fojaVacia(JsonNode n) {
    return n == null || n.isNull() || !n.isNumber() || n.asInt() <= 0;
  }

  private static ArrayNode unirTextos(JsonNode a, JsonNode b, int max) {
    LinkedHashSet<String> items = new LinkedHashSet<>();
    agregarTextos(items, a);
    agregarTextos(items, b);
    ArrayNode arr = JSON.createArrayNode();
    int n = 0;
    for (String s : items) {
      if (n++ >= max) {
        break;
      }
      arr.add(s);
    }
    return arr;
  }

  private static void agregarTextos(LinkedHashSet<String> items, JsonNode arr) {
    if (arr == null || !arr.isArray()) {
      return;
    }
    for (JsonNode item : arr) {
      String v = item.isValueNode() ? item.asText("").trim() : item.toString();
      if (!v.isEmpty()) {
        items.add(v);
      }
    }
  }

  private static ArrayNode unirAcciones(JsonNode a, JsonNode b) {
    Map<String, ObjectNode> porClave = new LinkedHashMap<>();
    agregarAcciones(porClave, a);
    agregarAcciones(porClave, b);
    ArrayNode arr = JSON.createArrayNode();
    porClave.values().forEach(arr::add);
    return arr;
  }

  private static void agregarAcciones(Map<String, ObjectNode> porClave, JsonNode arr) {
    if (arr == null || !arr.isArray()) {
      return;
    }
    for (JsonNode item : arr) {
      if (!item.isObject()) {
        continue;
      }
      String codigo = item.path("codigo_accion").asText("").trim();
      String titulo = item.path("titulo").asText("").trim();
      String key = !codigo.isEmpty() ? codigo : titulo;
      if (key.isEmpty() || porClave.containsKey(key)) {
        continue;
      }
      porClave.put(key, ((ObjectNode) item).deepCopy());
    }
  }

  private static ArrayNode unirArrays(JsonNode a, JsonNode b) {
    ArrayNode out = JSON.createArrayNode();
    LinkedHashSet<String> vistos = new LinkedHashSet<>();
    agregarItems(out, vistos, a);
    agregarItems(out, vistos, b);
    return out;
  }

  private static void agregarItems(ArrayNode out, LinkedHashSet<String> vistos, JsonNode arr) {
    for (JsonNode item : arr) {
      if (vacio(item)) {
        continue;
      }
      String clave = item.isValueNode() ? item.asText() : item.toString();
      if (vistos.add(clave)) {
        out.add(item.deepCopy());
      }
    }
  }

  private static String codigoEtapa(ObjectNode nodo) {
    JsonNode sugerida = nodo.get("etapa_sugerida");
    if (sugerida != null && sugerida.isObject()) {
      String codigo = sugerida.path("codigo").asText("").trim();
      if (!codigo.isEmpty()) {
        return codigo;
      }
    }
    String detectada = nodo.path("etapa_procesal_detectada").asText("").trim();
    return detectada.isEmpty() ? null : detectada;
  }

  /** Gana el hito más avanzado. INDETERMINADA no pisa una etapa ya hallada. */
  private static boolean etapaMasAvanzada(String nueva, String actual) {
    return ordenEtapa(nueva) > ordenEtapa(actual);
  }

  private static int ordenEtapa(String codigo) {
    if (codigo == null || codigo.isBlank() || "INDETERMINADA".equalsIgnoreCase(codigo)) {
      return -1;
    }
    return CoactivaEtapa.parse(codigo).map(Enum::ordinal).orElse(-1);
  }

  private static boolean vacio(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) {
      return true;
    }
    if (n.isTextual()) {
      String s = n.asText().trim();
      return s.isEmpty() || "[DATO EN TEXTO NO EXTRAÍDO POR IA]".equals(s);
    }
    if (n.isArray() || n.isObject()) {
      return n.isEmpty();
    }
    return false;
  }

  private static String tipoPieza(CoactivaDiagnostico diag) {
    if (diag == null) {
      return null;
    }
    return diag.documentos().stream()
        .filter(Documento::presente)
        .map(Documento::tipo)
        .findFirst()
        .orElse(diag.etapaNormalizada());
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

  /** Solo con {@code lexia.coactivas.ia.dummy=true}: no propone etapa ni copia la del banco. */
  private static final String DUMMY_JSON =
      """
      {"porcentaje_completitud": 0,
       "etapa_procesal_detectada": "INDETERMINADA",
       "etapa_sugerida": {"codigo": "INDETERMINADA", "nombre": null,
         "justificacion_juridica": "Diagnóstico de prueba: no se leyó el OCR.", "evidencia": null, "evidencias": []},
       "documentos_identificados": [],
       "alertas_inconsistencias": ["Diagnóstico de prueba: LLM desactivado."],
       "datos_extraidos": {},
       "siguiente_accion_sugerida": null}
      """;

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

  private record Previo(UUID id, UUID archivoId, String json, Instant fecha) {}

  private record Resultado(
      String etapa, Long promptId, CoactivaDiagnostico diagnostico, String error, boolean calidad) {
    static Resultado ok(String etapa, Long promptId, CoactivaDiagnostico diagnostico) {
      return new Resultado(etapa, promptId, diagnostico, null, false);
    }

    static Resultado error(String etapa, Long promptId, String error) {
      return new Resultado(etapa, promptId, null, error, false);
    }

    static Resultado calidad(String etapa, Long promptId, String error) {
      return new Resultado(etapa, promptId, null, error, true);
    }
  }
}
