package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ActuacionResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.PreviewDocumentoRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.PublicarDocumentoRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.VariablesDocumentoResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.PlantillaDatos;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaVariableBinder.Resultado;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage.StoredFile;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaAnalisis;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaAnalisisRepository;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnosticoParser;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaGenerationService {

  private static final Pattern TIPO = Pattern.compile("[a-z0-9-]{1,64}");
  private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_]{1,80}");
  private static final Pattern CRITICA =
      Pattern.compile(
          "(?i)(numero_juicio|numero_proceso|numero_operacion|nombre_deudor|cedula_deudor|"
              + "nombre_garante|cedula_garante|numero_resolucion_delegacion)");
  private static final Set<String> OPI = Set.of("OPI_EMITIDA", "NOTIFICACION_COA");
  private static final String PREFIJO = "templates/coactivas/";
  private static final int MAX_VALOR = 500;
  private static final TypeReference<Map<String, String>> MAPA = new TypeReference<>() {};

  private final AuthorizationService authorization;
  private final CoactivaExpedienteService expedientes;
  private final CoactivaPlantillaRepository plantillas;
  private final CoactivaDocumentoDraftRepository drafts;
  private final CoactivaActuacionRepository actuaciones;
  private final CoactivaArchivoRepository archivos;
  private final CoactivaArchivoStorage storage;
  private final CoactivaPlantillaDataMapper mapper;
  private final CoactivaAnalisisRepository analisis;
  private final CoactivaDiagnosticoParser parser;
  private final CoactivaVariableBinder binder;
  private final DocxMinutaRenderer renderer;
  private final DocxPdfConverter pdf;
  private final ObjectMapper json;
  private final Path storageDir;
  private final ZoneId zona;

  public CoactivaGenerationService(
      AuthorizationService authorization,
      CoactivaExpedienteService expedientes,
      CoactivaPlantillaRepository plantillas,
      CoactivaDocumentoDraftRepository drafts,
      CoactivaActuacionRepository actuaciones,
      CoactivaArchivoRepository archivos,
      CoactivaArchivoStorage storage,
      CoactivaPlantillaDataMapper mapper,
      CoactivaAnalisisRepository analisis,
      CoactivaDiagnosticoParser parser,
      DocxMinutaRenderer renderer,
      DocxPdfConverter pdf,
      ObjectMapper json,
      @Value("${lexia.coactivas.documentos.storage-dir:./data/coactivas}") String storageDir,
      @Value("${lexia.coactivas.plantillas.zona-horaria:America/Guayaquil}") String zonaHoraria) {
    this.authorization = authorization;
    this.expedientes = expedientes;
    this.plantillas = plantillas;
    this.drafts = drafts;
    this.actuaciones = actuaciones;
    this.archivos = archivos;
    this.storage = storage;
    this.mapper = mapper;
    this.analisis = analisis;
    this.parser = parser;
    this.binder = new CoactivaVariableBinder();
    this.renderer = renderer;
    this.pdf = pdf;
    this.json = json;
    this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    this.zona = ZoneId.of(zonaHoraria);
  }

  @Transactional(readOnly = true)
  public VariablesDocumentoResponse variables(UUID expedienteId, String tipo) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    AuthPrincipal principal = AuthContext.require();
    Contexto ctx = contexto(principal.tenantId(), expedienteId, tipo);
    CoactivaDocumentoDraft draft =
        drafts
            .findByTenantIdAndExpedienteIdAndActuacionTipo(
                principal.tenantId(), expedienteId, ctx.tipo())
            .orElse(null);
    Resultado resuelto = resolver(ctx, draft == null ? Map.of() : leerOverrides(draft.getOverrides()));
    return respuesta(draft, ctx.plantilla(), resuelto);
  }

  @Transactional
  public Preview previsualizar(UUID expedienteId, String tipo, PreviewDocumentoRequest request) {
    authorization.requirePermission(CoactivaPermisos.GENERAR_DOCUMENTOS);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    Contexto ctx = contexto(tenantId, expedienteId, tipo);
    CoactivaDocumentoDraft draft =
        drafts
            .findByTenantIdAndExpedienteIdAndActuacionTipo(tenantId, expedienteId, ctx.tipo())
            .orElseGet(
                () ->
                    drafts.save(
                        CoactivaDocumentoDraft.crear(
                            tenantId,
                            expedienteId,
                            ctx.plantilla().getId(),
                            ctx.tipo(),
                            principal.userId())));
    Map<String, String> overrides = leerOverrides(draft.getOverrides());
    aplicar(overrides, ctx.tags(), request);
    Resultado resuelto = resolver(ctx, overrides);
    byte[] docx = renderer.renderPlantilla(ctx.ruta(), new LinkedHashMap<>(resuelto.variables()));
    Path stored = persistDocx(draft.getId(), ctx.plantilla().getNombre(), docx);
    draft.guardar(
        escribir(resuelto.variables()),
        escribir(resuelto.datosExtraidos()),
        escribir(overrides),
        stored.toString(),
        resuelto.pendientes().size());
    drafts.save(draft);
    return new Preview(draft.getId(), pdf.toPdf(docx), respuesta(draft, ctx.plantilla(), resuelto));
  }

  public record Preview(UUID draftId, byte[] pdf, VariablesDocumentoResponse variables) {}

  @Transactional(readOnly = true)
  public Descarga descargar(UUID draftId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    AuthPrincipal principal = AuthContext.require();
    CoactivaDocumentoDraft draft =
        drafts
            .findByIdAndTenantId(draftId, principal.tenantId())
            .orElseThrow(() -> ApiException.notFound("Borrador no encontrado."));
    CoactivaPlantilla plantilla =
        plantillas
            .findByIdAndTenantIdAndActivoTrue(draft.getPlantillaId(), principal.tenantId())
            .orElseThrow(() -> ApiException.notFound("Plantilla no encontrada."));
    String nombre = nombreArchivo(plantilla.getNombre(), ".docx");
    return new Descarga(nombre, leerDocx(draft));
  }

  public record Descarga(String nombre, byte[] bytes) {}

  @Transactional
  public ActuacionResponse publicar(UUID expedienteId, String tipo, PublicarDocumentoRequest request) {
    authorization.requirePermission(CoactivaPermisos.GENERAR_DOCUMENTOS);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    Contexto ctx = contexto(tenantId, expedienteId, tipo);
    CoactivaDocumentoDraft draft =
        drafts
            .findByTenantIdAndExpedienteIdAndActuacionTipo(tenantId, expedienteId, ctx.tipo())
            .orElseThrow(() -> ApiException.badRequest("Actualiza la previsualización antes de continuar a firma."));
    if (draft.getStoragePath() == null || draft.getStoragePath().isBlank()) {
      throw ApiException.badRequest("Actualiza la previsualización antes de continuar a firma.");
    }
    Resultado resuelto = resolver(ctx, leerOverrides(draft.getOverrides()));
    List<String> criticas = criticas(resuelto.pendientes());
    boolean permitir = request != null && Boolean.TRUE.equals(request.permitirIncompleto());
    if (!criticas.isEmpty() && !permitir) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "VARIABLES_CRITICAS",
          "Faltan datos críticos del documento.",
          List.of(),
          Map.of("variablesFaltantes", criticas));
    }
    byte[] pdfBytes = pdf.toPdf(leerDocx(draft));
    String nombre = nombreArchivo(ctx.plantilla().getNombre(), ".pdf");
    UUID archivoId = UUID.randomUUID();
    StoredFile stored = storage.storeBytes(tenantId, archivoId, nombre, pdfBytes);
    CoactivaArchivo archivo =
        CoactivaArchivo.create(
            archivoId,
            tenantId,
            "ACTUACION",
            nombre,
            "application/pdf",
            stored.size(),
            stored.sha256(),
            stored.path(),
            principal.userId());
    archivo.vincularExpediente(expedienteId);
    archivos.save(archivo);
    CoactivaActuacion actuacion =
        actuaciones.save(
            CoactivaActuacion.crear(
                tenantId, expedienteId, ctx.plantilla().getId(), archivoId, null, principal.userId()));
    draft.marcarListo(actuacion.getId());
    drafts.save(draft);
    expedientes.registrarEvento(
        tenantId,
        expedienteId,
        "ACTUACION",
        "Documento listo para firma: " + ctx.plantilla().getNombre(),
        criticas.isEmpty()
            ? "Estado GENERADO."
            : "Estado GENERADO. Campos críticos confirmados en nodata: " + String.join(", ", criticas),
        principal.userId(),
        archivoId);
    ctx.expediente().setFechaUltimaActuacion(LocalDate.now(zona));
    ctx.expediente().touch(principal.userId());
    return new ActuacionResponse(actuacion.getId(), archivoId, actuacion.getEstado());
  }

  private Resultado resolver(Contexto ctx, Map<String, String> overrides) {
    PlantillaDatos datos = ctx.datos();
    return binder.resolver(
        ctx.tags(), capaIa(ctx.expediente(), datos), capaSistema(datos), overrides, LocalDate.now(zona));
  }

  private Map<String, String> capaIa(CoactivaExpediente expediente, PlantillaDatos datos) {
    Map<String, String> ia = new LinkedHashMap<>();
    analisis
        .findFirstByTenantIdAndExpedienteIdAndEstadoOrderByCreatedAtDesc(
            expediente.getTenantId(), expediente.getId(), CoactivaAnalisis.ANALIZADO)
        .map(a -> parser.parse(a.getResultado()))
        .filter(parse -> parse.diagnostico() != null)
        .map(parse -> parse.diagnostico().datosExtraidos())
        .ifPresent(extraidos -> aplanar(extraidos, ia));
    datos.valores()
        .forEach(
            (tag, valor) -> {
              if (CoactivaVariableBinder.esSistema(tag)) {
                return;
              }
              String texto = CoactivaVariableBinder.limpio(valor == null ? null : valor.toString());
              if (texto != null) {
                ia.put(tag, texto);
              }
            });
    return ia;
  }

  private static Map<String, String> capaSistema(PlantillaDatos datos) {
    Map<String, String> sistema = new LinkedHashMap<>();
    datos.valores()
        .forEach(
            (tag, valor) -> {
              if (!CoactivaVariableBinder.esSistema(tag)) {
                return;
              }
              String texto = CoactivaVariableBinder.limpio(valor == null ? null : valor.toString());
              if (texto != null) {
                sistema.put(tag, texto);
              }
            });
    return sistema;
  }

  private static void aplanar(Object node, Map<String, String> out) {
    if (!(node instanceof Map<?, ?> map)) {
      return;
    }
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = String.valueOf(entry.getKey());
      Object value = entry.getValue();
      if (value instanceof Map<?, ?>) {
        aplanar(value, out);
      } else if (value instanceof Collection<?> lista) {
        List<String> partes = new ArrayList<>();
        for (Object item : lista) {
          if (item instanceof Map<?, ?>) {
            aplanar(item, out);
          } else {
            String texto = CoactivaVariableBinder.limpio(item == null ? null : item.toString());
            if (texto != null) {
              partes.add(texto);
            }
          }
        }
        if (!partes.isEmpty()) {
          out.putIfAbsent(key, String.join(", ", partes));
        }
      } else {
        String texto = CoactivaVariableBinder.limpio(value == null ? null : value.toString());
        if (texto != null) {
          out.put(key, texto);
        }
      }
    }
  }

  private Contexto contexto(UUID tenantId, UUID expedienteId, String tipoRaw) {
    String tipo = tipoRaw == null ? "" : tipoRaw.trim().toLowerCase(Locale.ROOT);
    if (!TIPO.matcher(tipo).matches()) {
      throw ApiException.badRequest("Tipo de actuación desconocido.");
    }
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    if (expediente.getEtapaVerificada() == null) {
      throw ApiException.badRequest("Confirma la etapa del expediente antes de generar la actuación.");
    }
    CoactivaPlantilla plantilla =
        plantillas
            .findByTenantIdAndCodigoAndActivoTrue(tenantId, tipo)
            .orElseThrow(() -> ApiException.notFound("Plantilla no encontrada."));
    if (!compatible(plantilla.getEtapa(), expediente.getEtapaVerificada())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "PLANTILLA_ETAPA",
          "La plantilla no corresponde a la etapa verificada del expediente.");
    }
    String ruta = ruta(plantilla);
    return new Contexto(expediente, plantilla, tipo, ruta, renderer.tags(ruta), mapper.mapear(expediente));
  }

  private record Contexto(
      CoactivaExpediente expediente,
      CoactivaPlantilla plantilla,
      String tipo,
      String ruta,
      List<String> tags,
      PlantillaDatos datos) {}

  private VariablesDocumentoResponse respuesta(
      CoactivaDocumentoDraft draft, CoactivaPlantilla plantilla, Resultado resuelto) {
    boolean conArchivo = draft != null && draft.getStoragePath() != null && !draft.getStoragePath().isBlank();
    return new VariablesDocumentoResponse(
        draft == null ? null : draft.getId(),
        plantilla.getCodigo(),
        plantilla.getNombre(),
        draft == null ? null : draft.getStatus(),
        resuelto.variables(),
        resuelto.pendientes(),
        resuelto.origenes(),
        resuelto.valoresExtraidos(),
        etiquetas(resuelto.variables().keySet()),
        resuelto.liquidacionVigente(),
        conArchivo ? "/api/v1/coactivas/actuaciones/" + draft.getId() + "/download" : null);
  }

  private static Map<String, String> etiquetas(Set<String> tags) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String tag : tags) {
      String[] partes = tag.split("_");
      StringBuilder etiqueta = new StringBuilder();
      for (String parte : partes) {
        if (parte.isBlank()) {
          continue;
        }
        if (!etiqueta.isEmpty()) {
          etiqueta.append(' ');
        }
        etiqueta.append(Character.toUpperCase(parte.charAt(0))).append(parte.substring(1));
      }
      out.put(tag, etiqueta.toString());
    }
    return out;
  }

  private static List<String> criticas(List<String> pendientes) {
    return pendientes.stream().filter(tag -> CRITICA.matcher(tag).find()).toList();
  }

  private static void aplicar(Map<String, String> overrides, List<String> tags, PreviewDocumentoRequest request) {
    if (request == null) {
      return;
    }
    Set<String> permitidas = Set.copyOf(tags);
    if (request.restaurar() != null) {
      for (String tag : request.restaurar()) {
        if (tag != null) {
          overrides.remove(tag.trim());
        }
      }
    }
    if (request.variables() == null) {
      return;
    }
    for (Map.Entry<String, String> entry : request.variables().entrySet()) {
      String tag = entry.getKey() == null ? "" : entry.getKey().trim();
      if (!TAG.matcher(tag).matches() || !permitidas.contains(tag)) {
        continue;
      }
      String valor = entry.getValue() == null ? "" : entry.getValue().trim();
      overrides.put(tag, valor.length() > MAX_VALOR ? valor.substring(0, MAX_VALOR) : valor);
    }
  }

  private Map<String, String> leerOverrides(String raw) {
    if (raw == null || raw.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      Map<String, String> leido = json.readValue(raw, MAPA);
      return leido == null ? new LinkedHashMap<>() : new LinkedHashMap<>(leido);
    } catch (IOException e) {
      return new LinkedHashMap<>();
    }
  }

  private String escribir(Map<String, String> valores) {
    try {
      return json.writeValueAsString(valores);
    } catch (JsonProcessingException e) {
      throw ApiException.badRequest("No se pudieron guardar las variables del documento.");
    }
  }

  private Path persistDocx(UUID draftId, String nombre, byte[] bytes) {
    try {
      Files.createDirectories(storageDir);
      String safe = nombreArchivo(nombre, ".docx").replaceAll("[^a-zA-Z0-9._-]", "_");
      Path target = storageDir.resolve(draftId + "_" + safe);
      Files.write(target, bytes);
      return target;
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo guardar el DOCX generado.");
    }
  }

  private byte[] leerDocx(CoactivaDocumentoDraft draft) {
    if (draft.getStoragePath() == null || draft.getStoragePath().isBlank()) {
      throw ApiException.badRequest("Todavía no hay un .docx generado.");
    }
    Path path = Path.of(draft.getStoragePath()).toAbsolutePath().normalize();
    if (!path.startsWith(storageDir)) {
      throw ApiException.badRequest("La ruta del borrador no es válida.");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el DOCX generado.");
    }
  }

  private static boolean compatible(String plantillaEtapa, String etapaVerificada) {
    return plantillaEtapa.equals(etapaVerificada)
        || (OPI.contains(plantillaEtapa) && OPI.contains(etapaVerificada));
  }

  private static String ruta(CoactivaPlantilla plantilla) {
    if (!plantilla.generable()) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PLANTILLA_SIN_DOCX",
          "El formato " + plantilla.getNombre() + " todavía no tiene una plantilla .docx asociada.");
    }
    String ruta = plantilla.getRutaDocx().trim();
    if (!ruta.startsWith(PREFIJO) || !ruta.endsWith(".docx") || ruta.contains("..")) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "PLANTILLA_RUTA_INVALIDA", "La ruta de la plantilla no es válida.");
    }
    return ruta;
  }

  private static String nombreArchivo(String nombre, String extension) {
    String base = nombre == null ? "actuacion" : nombre.replaceAll("(?i)\\.docx$", "").trim();
    if (base.isBlank()) {
      base = "actuacion";
    }
    return base + extension;
  }
}
