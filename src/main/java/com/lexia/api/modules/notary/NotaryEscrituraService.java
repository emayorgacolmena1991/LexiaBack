package com.lexia.api.modules.notary;

import com.deepoove.poi.data.PictureRenderData;
import com.deepoove.poi.data.PictureType;
import com.deepoove.poi.data.Pictures;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.ExpedienteDocumentoStorage;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import com.lexia.api.modules.expedientes.minutas.MinutaTemplateCatalog;
import com.lexia.api.modules.expedientes.minutas.MinutaTemplateDescriptor;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Escritura que genera la notaría destino sobre la versión vigente del envío. La notaría solo
 * llena lo que falta: lo que trae el bufete, los derivados y el perfil no se editan.
 */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class NotaryEscrituraService {

  static final String KIND = "ESCRITURA_NOTARIAL";
  static final String ENTIDAD_BIESS = "BANCO DEL INSTITUTO ECUATORIANO DE SEGURIDAD SOCIAL - BIESS";
  static final String ORIGEN_NOTARIA = "NOTARIA_FORMULARIO";
  static final String ORIGEN_BUFETE = "BUFETE_FALTANTE";
  static final String VERSION_MIGRADA =
      "Esta versión no tiene datos de minuta; pida al bufete que edite el envío";

  private static final Set<String> CAMPOS_NOTARIA =
      Set.of(
          "fecha_otorgamiento",
          "notaria_titular_nombre",
          "notaria_numero_literal",
          "canton_notaria",
          "provincia_notaria");
  private static final Set<String> ESTADOS_PERMITIDOS =
      Set.of(NotaryRemisionEstado.RECIBIDO.name(), NotaryRemisionEstado.EN_TRAMITE.name());
  /** Todos los productos del catálogo de minutas son créditos BIESS. */
  private static final Set<String> PRODUCTOS_BIESS =
      Set.of(
          MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS,
          MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF,
          MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND,
          MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_SOLID,
          MinutaTemplateCatalog.PRODUCT_TERRENO_Y_VIVIENDA,
          MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA);
  private static final int ANCHO_LOGO = 120;
  private static final String CARPETA = "escrituras";
  private static final String MIME_DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final TypeReference<List<String>> LISTA = new TypeReference<>() {};

  private final AuthorizationService authorization;
  private final NotaryRemisionRepository remisiones;
  private final NotaryRemisionVersionRepository versiones;
  private final NotaryEscrituraRepository escrituras;
  private final NotaryTenantProfileRepository perfiles;
  private final NotaryLogoService logos;
  private final DocxMinutaRenderer renderer;
  private final DocxPdfConverter pdf;
  private final MinutaTemplateCatalog catalogo;
  private final ExpedienteDocumentoStorage storage;
  private final AppUserRepository usuarios;
  private final AuditEventRepository auditEvents;
  private final ObjectMapper mapper;

  public NotaryEscrituraService(
      AuthorizationService authorization,
      NotaryRemisionRepository remisiones,
      NotaryRemisionVersionRepository versiones,
      NotaryEscrituraRepository escrituras,
      NotaryTenantProfileRepository perfiles,
      NotaryLogoService logos,
      DocxMinutaRenderer renderer,
      DocxPdfConverter pdf,
      MinutaTemplateCatalog catalogo,
      ExpedienteDocumentoStorage storage,
      AppUserRepository usuarios,
      AuditEventRepository auditEvents,
      ObjectMapper mapper) {
    this.authorization = authorization;
    this.remisiones = remisiones;
    this.versiones = versiones;
    this.escrituras = escrituras;
    this.perfiles = perfiles;
    this.logos = logos;
    this.renderer = renderer;
    this.pdf = pdf;
    this.catalogo = catalogo;
    this.storage = storage;
    this.usuarios = usuarios;
    this.auditEvents = auditEvents;
    this.mapper = mapper;
  }

  /** Lo que la escritura necesita y aún no tiene, con prellenado del perfil de la notaría. */
  @Transactional(readOnly = true)
  public NotaryDtos.EscrituraFormularioDto formulario(UUID remisionId) {
    Preparacion p = preparar(remisionId);
    List<NotaryDtos.CampoFormularioDto> campos =
        p.formulario().stream()
            .map(
                clave ->
                    new NotaryDtos.CampoFormularioDto(
                        clave,
                        MinutaViviendaData.etiquetaCorta(clave),
                        prellenado(clave, p.perfil()),
                        CAMPOS_NOTARIA.contains(clave) ? ORIGEN_NOTARIA : ORIGEN_BUFETE))
            .toList();
    return new NotaryDtos.EscrituraFormularioDto(p.version().getVersionNo(), campos);
  }

  @Transactional
  public NotaryDtos.EscrituraDto generar(UUID remisionId, NotaryDtos.GenerarEscrituraBody body) {
    Preparacion p = preparar(remisionId);
    Render render = renderDocx(p, validarFormulario(body, p.formulario()));

    NotaryRemision remision = p.remision();
    UUID id = UUID.randomUUID();
    String nombre =
        "escritura_" + remision.getExpedienteCodigo() + "_v" + p.version().getVersionNo() + ".docx";
    String ruta =
        storage
            .writeEnCarpeta(remision.getNotaryTenantId(), CARPETA, id, nombre, MIME_DOCX, render.docx())
            .clave();
    storage.borrarSiHayRollback(ruta);
    AuthPrincipal principal = AuthContext.require();
    NotaryEscritura escritura =
        escrituras.save(
            NotaryEscritura.of(
                id,
                remision.getId(),
                p.version().getVersionNo(),
                json(render.enviados()),
                json(render.vacios()),
                ruta,
                nombre,
                principal.userId()));
    auditEvents.save(
        AuditEvent.of(
            remision.getNotaryTenantId(),
            principal.userId(),
            "NOTARY_ESCRITURA_GENERATED",
            "notary_remision",
            remision.getId(),
            "OK",
            null,
            null));
    return toDto(escritura);
  }

  /**
   * Re-render de la plantilla con los valores del panel, sin guardar. Misma UX que actualizar
   * previsualización en Escrituración.
   */
  @Transactional(readOnly = true)
  public byte[] previewConValores(UUID remisionId, NotaryDtos.GenerarEscrituraBody body) {
    Preparacion p = preparar(remisionId);
    Render render = renderDocx(p, validarFormulario(body, p.formulario()));
    return pdf.toPdf(render.docx());
  }

  @Transactional(readOnly = true)
  public List<NotaryDtos.EscrituraDto> historial(UUID remisionId) {
    NotaryRemision remision = remisionLegible(remisionId);
    return escrituras.findByRemisionIdOrderByGeneratedAtDesc(remision.getId()).stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public NotaryDtos.RemisionDocumentContent archivo(UUID remisionId, UUID escrituraId) {
    NotaryRemision remision = remisionLegible(remisionId);
    NotaryEscritura escritura =
        escrituras
            .findByIdAndRemisionId(escrituraId, remision.getId())
            .orElseThrow(() -> NotaryBusinessException.notFound("Escritura no encontrada."));
    return new NotaryDtos.RemisionDocumentContent(
        escritura.getFileName(), MIME_DOCX, storage.read(escritura.getStorageRef()));
  }

  /** PDF de una escritura ya generada (mismo .docx de descarga). */
  @Transactional(readOnly = true)
  public byte[] previewPdf(UUID remisionId, UUID escrituraId) {
    return pdf.toPdf(archivo(remisionId, escrituraId).bytes());
  }

  /** Datos que no vienen del formulario: base del bufete, derivados y perfil. */
  private record Preparacion(
      NotaryRemision remision,
      NotaryRemisionVersion version,
      MinutaTemplateDescriptor plantilla,
      NotaryTenantProfile perfil,
      List<String> tags,
      Map<String, Object> cubiertos,
      List<String> formulario) {}

  private record Render(byte[] docx, Map<String, String> enviados, List<String> vacios) {}

  private Render renderDocx(Preparacion p, Map<String, String> enviados) {
    Map<String, Object> datos = new LinkedHashMap<>(p.cubiertos());
    datos.putAll(enviados);
    List<String> vacios = new ArrayList<>();
    for (String tag : p.tags()) {
      Object valor = datos.get(tag);
      if (valor == null || (valor instanceof String texto && texto.isBlank())) {
        datos.put(tag, "");
        vacios.add(tag);
      }
    }
    byte[] docx = renderer.renderPlantilla(p.plantilla().classpathResource(), datos);
    return new Render(docx, enviados, vacios);
  }

  private Preparacion preparar(UUID remisionId) {
    NotaryRemision remision = RemisionesVisibles.deLaNotaria(remisiones, remisionId);
    authorization.requirePermission("notaria:remision:actuar");
    if (!ESTADOS_PERMITIDOS.contains(remision.getEstado())) {
      throw NotaryBusinessException.conflict(
          "La escritura solo se genera con la remisión recibida o en trámite (estado actual: "
              + remision.getEstado()
              + ").");
    }
    NotaryRemisionVersion version =
        versiones
            .findFirstByRemisionIdOrderByVersionNoDesc(remision.getId())
            .orElseThrow(() -> NotaryBusinessException.conflict(VERSION_MIGRADA));
    String producto = version.getProductCode();
    if (!StringUtils.hasText(producto)) {
      throw NotaryBusinessException.conflict(VERSION_MIGRADA);
    }
    Map<String, Object> base = datosBase(version);
    MinutaTemplateDescriptor plantilla =
        catalogo
            .find(producto, KIND)
            .orElseThrow(
                () ->
                    NotaryBusinessException.conflict(
                        "No hay plantilla de escritura notarial para el producto " + producto));
    NotaryTenantProfile perfil = perfiles.findById(remision.getNotaryTenantId()).orElse(null);

    Map<String, Object> cubiertos = new LinkedHashMap<>(base);
    if (PRODUCTOS_BIESS.contains(producto)) {
      cubiertos.put("nombre_entidad_crediticia", ENTIDAD_BIESS);
    }
    if (perfil != null && StringUtils.hasText(perfil.getCanton())) {
      cubiertos.put("canton_notaria", perfil.getCanton().trim());
    }
    cubiertos.put("logo_notaria", logo(remision.getNotaryTenantId()));

    List<String> tags = renderer.tags(plantilla.classpathResource());
    List<String> formulario =
        tags.stream().filter(tag -> !cubiertos.containsKey(tag)).toList();
    return new Preparacion(remision, version, plantilla, perfil, tags, cubiertos, formulario);
  }

  /** Payload = JSON de MinutaViviendaData, como en loadData. Solo cuenta lo que trae valor. */
  private Map<String, Object> datosBase(NotaryRemisionVersion version) {
    JsonNode payload;
    try {
      payload = mapper.readTree(version.getPayload() == null ? "" : version.getPayload());
    } catch (JsonProcessingException ex) {
      throw payloadIlegible();
    }
    if (payload == null || !payload.isObject() || payload.isEmpty()) {
      throw NotaryBusinessException.conflict(VERSION_MIGRADA);
    }
    MinutaViviendaData data;
    try {
      data = mapper.treeToValue(payload, MinutaViviendaData.class);
    } catch (JsonProcessingException ex) {
      throw payloadIlegible();
    }
    Map<String, Object> base = new LinkedHashMap<>();
    data.toTemplateMap()
        .forEach(
            (clave, valor) -> {
              if (!MinutaViviendaData.isMissing(valor)) {
                base.put(clave, valor);
              }
            });
    return base;
  }

  private static String prellenado(String clave, NotaryTenantProfile perfil) {
    if (perfil == null) {
      return "";
    }
    return switch (clave) {
      case "notaria_titular_nombre" -> textoOVacio(perfil.getNotaryHolderName());
      case "notaria_numero_literal" -> {
        String numero = textoOVacio(perfil.getNotaryNumber());
        yield OrdinalFemenino.de(numero).orElse(numero);
      }
      default -> "";
    };
  }

  private static String textoOVacio(String valor) {
    return valor == null ? "" : valor.trim();
  }

  private static ApiException payloadIlegible() {
    return NotaryBusinessException.conflict(
        "Los datos de la minuta de esta versión no se pueden leer.");
  }

  /** La notaría solo llena lo que falta: corregir al bufete es devolver la remisión. */
  private static Map<String, String> validarFormulario(
      NotaryDtos.GenerarEscrituraBody body, List<String> formulario) {
    Map<String, String> valores = body == null || body.valores() == null ? Map.of() : body.valores();
    Set<String> permitidos = new LinkedHashSet<>(formulario);
    List<String> desconocidas =
        valores.keySet().stream().filter(clave -> !permitidos.contains(clave)).toList();
    if (!desconocidas.isEmpty()) {
      throw NotaryBusinessException.badRequest(
          "Campos que la notaría no puede completar: " + String.join(", ", desconocidas));
    }
    Map<String, String> limpios = new LinkedHashMap<>();
    valores.forEach((clave, valor) -> limpios.put(clave, valor == null ? "" : valor.trim()));
    return limpios;
  }

  /** Ancho 120 px con su proporción; el pixel transparente se deja en 1×1 para no ocupar espacio. */
  private PictureRenderData logo(UUID notariaId) {
    byte[] bytes = logos.logoParaRender(notariaId);
    int[] dimensiones = dimensiones(bytes);
    boolean pixel = dimensiones[0] <= 1 && dimensiones[1] <= 1;
    int ancho = pixel ? 1 : ANCHO_LOGO;
    int alto =
        pixel ? 1 : Math.max(1, Math.round((float) ANCHO_LOGO * dimensiones[1] / dimensiones[0]));
    PictureType tipo = bytes.length > 0 && bytes[0] == (byte) 0x89 ? PictureType.PNG : PictureType.JPEG;
    return Pictures.ofBytes(bytes, tipo).size(ancho, alto).create();
  }

  private static int[] dimensiones(byte[] bytes) {
    try (ImageInputStream entrada =
        ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> lectores = ImageIO.getImageReaders(entrada);
      if (!lectores.hasNext()) {
        throw new IllegalStateException("El logo guardado no es una imagen legible.");
      }
      ImageReader lector = lectores.next();
      try {
        lector.setInput(entrada, true, true);
        return new int[] {lector.getWidth(0), lector.getHeight(0)};
      } finally {
        lector.dispose();
      }
    } catch (IOException ex) {
      throw new IllegalStateException("El logo guardado no es una imagen legible.", ex);
    }
  }

  /** Historial y archivos: notaría destino con permiso de lectura. */
  private NotaryRemision remisionLegible(UUID remisionId) {
    NotaryRemision remision = RemisionesVisibles.deLaNotaria(remisiones, remisionId);
    authorization.requirePermission("notaria:remision:leer");
    return remision;
  }

  private NotaryDtos.EscrituraDto toDto(NotaryEscritura escritura) {
    return new NotaryDtos.EscrituraDto(
        escritura.getId(),
        escritura.getVersionNo(),
        escritura.getFileName(),
        escritura.getGeneratedAt(),
        escritura.getGeneratedBy() == null
            ? null
            : usuarios.findById(escritura.getGeneratedBy()).map(AppUser::getDisplayName).orElse(null),
        leerLista(escritura.getCamposVacios()),
        leerMapa(escritura.getDatosFormulario()));
  }

  private List<String> leerLista(String json) {
    try {
      return json == null ? List.of() : mapper.readValue(json, LISTA);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("campos_vacios ilegible", ex);
    }
  }

  private Map<String, String> leerMapa(String json) {
    try {
      return json == null || json.isBlank()
          ? Map.of()
          : mapper.readValue(json, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("datos_formulario ilegible", ex);
    }
  }

  private String json(Object valor) {
    try {
      return mapper.writeValueAsString(valor);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("No se pudo serializar la escritura", ex);
    }
  }
}
