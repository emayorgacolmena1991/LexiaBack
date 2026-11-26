package com.lexia.api.modules.expedientes.requisitos;

import com.lexia.api.modules.expedientes.documentos.DocumentRequirement;
import com.lexia.api.modules.expedientes.documentos.DocumentRequirementRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.identity.CatalogItem;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Resolución única de requisitos documentales: dado producto, cantón y condiciones confirmadas,
 * devuelve los requisitos aplicables con obligatorio, esencial, por persona y rol. La usan la
 * clasificación (catálogo), el análisis IA ({@code <tipos_documento>} y faltantes), EJD.V1, la
 * ingesta masiva, el wizard y la ficha: todos ven la misma lista.
 *
 * <p>Reglas: filas del cantón propio pisan a las generales ({@code ALL}); un cantón sin filas
 * propias usa solo las generales (sin caer en Guayaquil); una fila condicional aplica solo si su
 * condición está confirmada; OTRO nunca es requisito.
 */
@Service
public class RequisitosService {

  public static final String CANTON_GENERAL = "ALL";
  private static final String CATALOGO_TIPOS = "DOC_TYPE";
  /** Producto preferencial: CREDITO_PREFERENCIAL aplica sin confirmarla (fase 3 la persiste). */
  static final String PRODUCTO_PREFERENCIAL = "VIV_TERMINADA_PREF";

  public static final String AVISO_SIN_CANTON =
      "El expediente no tiene cantón: se aplican solo los requisitos generales.";

  private final DocumentRequirementRepository requirements;
  private final ExpedienteCondicionRepository condiciones;
  private final CatalogRepository catalogs;
  private final CatalogItemRepository items;

  public RequisitosService(
      DocumentRequirementRepository requirements,
      ExpedienteCondicionRepository condiciones,
      CatalogRepository catalogs,
      CatalogItemRepository items) {
    this.requirements = requirements;
    this.condiciones = condiciones;
    this.catalogs = catalogs;
    this.items = items;
  }

  /** Requisito aplicable al caso. {@code condicion} null = aplica siempre. */
  public record RequisitoAplicable(
      String tipo,
      String descripcion,
      boolean obligatorio,
      boolean esencial,
      boolean porPersona,
      String rolPersona,
      String condicion,
      String canton,
      int maxValidityDays,
      int sortOrder) {}

  /**
   * @param canton cantón normalizado ({@code ALL} si no hay).
   * @param cantonPropio true si el cantón tiene filas propias en la matriz.
   * @param avisos mensajes para el usuario (p. ej. sin cantón).
   */
  public record Resolucion(
      String productCode,
      String canton,
      boolean cantonPropio,
      List<String> condiciones,
      List<RequisitoAplicable> requisitos,
      List<String> avisos) {

    public static Resolucion vacia(String productCode, String canton) {
      return new Resolucion(productCode, canton, false, List.of(), List.of(), List.of());
    }

    /** Lo que bloquea el avance (EJD.V1): obligatorio y esencial. */
    public List<RequisitoAplicable> esenciales() {
      return requisitos.stream().filter(r -> r.obligatorio() && r.esencial()).toList();
    }
  }

  public record TipoDocumental(String codigo, String nombre) {}

  /** Requisitos con las condiciones confirmadas del borrador o expediente ({@code null} = ninguna). */
  @Transactional(readOnly = true)
  public Resolucion resolverExpediente(
      UUID tenantId, UUID expedienteId, String productCode, String canton) {
    Set<String> confirmadas =
        expedienteId == null ? Set.of() : condicionesConfirmadas(tenantId, expedienteId);
    return resolver(tenantId, productCode, canton, confirmadas);
  }

  @Transactional(readOnly = true)
  public Set<String> condicionesConfirmadas(UUID tenantId, UUID expedienteId) {
    Set<String> out = new LinkedHashSet<>();
    for (ExpedienteCondicion c : condiciones.findByTenantIdAndExpedienteId(tenantId, expedienteId)) {
      if (c.vigente()) {
        out.add(c.getCondicion());
      }
    }
    return out;
  }

  @Transactional(readOnly = true)
  public Resolucion resolver(
      UUID tenantId, String productCode, String canton, Collection<String> condicionesConfirmadas) {
    String producto = StringUtils.hasText(productCode) ? productCode.trim().toUpperCase(Locale.ROOT) : null;
    String cantonNorm = normalizarCanton(canton);
    List<String> avisos = new ArrayList<>();
    if (CANTON_GENERAL.equals(cantonNorm)) {
      avisos.add(AVISO_SIN_CANTON);
    }
    if (tenantId == null || producto == null) {
      return new Resolucion(producto, cantonNorm, false, List.of(), List.of(), List.copyOf(avisos));
    }
    Set<String> activas = condicionesEfectivas(producto, condicionesConfirmadas);
    List<DocumentRequirement> filas =
        requirements.findByTenantIdAndProductCodeOrderBySortOrderAsc(tenantId, producto);
    boolean cantonPropio =
        !CANTON_GENERAL.equals(cantonNorm)
            && filas.stream().anyMatch(f -> cantonNorm.equalsIgnoreCase(f.getCanton()));
    return new Resolucion(
        producto,
        cantonNorm,
        cantonPropio,
        List.copyOf(activas),
        aplicables(filas, cantonNorm, activas),
        List.copyOf(avisos));
  }

  /** Condiciones confirmadas válidas + las implícitas del producto. */
  static Set<String> condicionesEfectivas(String producto, Collection<String> confirmadas) {
    Set<String> out = new LinkedHashSet<>();
    if (confirmadas != null) {
      for (String c : confirmadas) {
        CondicionCaso.de(c).ifPresent(cond -> out.add(cond.name()));
      }
    }
    if (PRODUCTO_PREFERENCIAL.equals(producto)) {
      out.add(CondicionCaso.CREDITO_PREFERENCIAL.name());
    }
    return out;
  }

  /**
   * Une las filas que aplican por tipo y rol. Gana la fila más específica (cantón propio sobre
   * {@code ALL}, condicional sobre general) para la descripción; obligatorio, esencial y por persona
   * se suman (basta una fila que lo pida). Si alguna fila general aplica, el requisito no depende de
   * condición.
   */
  static List<RequisitoAplicable> aplicables(
      List<DocumentRequirement> filas, String cantonNorm, Set<String> condicionesActivas) {
    Map<String, List<DocumentRequirement>> porClave = new LinkedHashMap<>();
    for (DocumentRequirement fila : filas) {
      String tipo = fila.getDocumentTypeCode();
      if (!StringUtils.hasText(tipo) || LegalDocument.TIPO_OTRO.equals(tipo.trim())) {
        continue;
      }
      boolean cantonAplica =
          CANTON_GENERAL.equalsIgnoreCase(fila.getCanton())
              || (!CANTON_GENERAL.equals(cantonNorm) && cantonNorm.equalsIgnoreCase(fila.getCanton()));
      boolean condicionAplica =
          !StringUtils.hasText(fila.getCondicion()) || condicionesActivas.contains(fila.getCondicion());
      if (!cantonAplica || !condicionAplica) {
        continue;
      }
      String clave = tipo.trim() + "|" + (fila.getRolPersona() == null ? "" : fila.getRolPersona());
      porClave.computeIfAbsent(clave, k -> new ArrayList<>()).add(fila);
    }
    List<RequisitoAplicable> out = new ArrayList<>();
    for (List<DocumentRequirement> grupo : porClave.values()) {
      DocumentRequirement principal =
          grupo.stream().max(Comparator.comparingInt(RequisitosService::especificidad)).orElseThrow();
      boolean general = grupo.stream().anyMatch(f -> !StringUtils.hasText(f.getCondicion()));
      out.add(
          new RequisitoAplicable(
              principal.getDocumentTypeCode().trim(),
              principal.getDescription(),
              grupo.stream().anyMatch(DocumentRequirement::isMandatory),
              grupo.stream().anyMatch(DocumentRequirement::isEsencial),
              grupo.stream().anyMatch(DocumentRequirement::isPorPersona),
              principal.getRolPersona(),
              general ? null : principal.getCondicion(),
              principal.getCanton(),
              principal.getMaxValidityDays(),
              grupo.stream().mapToInt(DocumentRequirement::getSortOrder).min().orElse(0)));
    }
    out.sort(Comparator.comparingInt(RequisitoAplicable::sortOrder).thenComparing(RequisitoAplicable::tipo));
    return List.copyOf(out);
  }

  private static int especificidad(DocumentRequirement fila) {
    int puntos = CANTON_GENERAL.equalsIgnoreCase(fila.getCanton()) ? 0 : 2;
    return puntos + (StringUtils.hasText(fila.getCondicion()) ? 1 : 0);
  }

  /**
   * Catálogo completo de tipos de escrituración del tenant (todos los de la matriz, de cualquier
   * producto, cantón o condición), con el nombre del catálogo DOC_TYPE. No incluye OTRO: lo agrega
   * quien muestra el selector.
   */
  @Transactional(readOnly = true)
  public List<TipoDocumental> catalogoTipos(UUID tenantId) {
    if (tenantId == null) {
      return List.of();
    }
    Map<String, String> nombres = new LinkedHashMap<>();
    catalogs
        .findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, CATALOGO_TIPOS)
        .ifPresent(
            c -> {
              for (CatalogItem item :
                  items.findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(c.getId(), tenantId)) {
                if (item.isActive()) {
                  nombres.put(item.getCode(), item.getLabel());
                }
              }
            });
    List<TipoDocumental> out = new ArrayList<>();
    for (String codigo : requirements.tiposDelTenant(tenantId)) {
      if (!StringUtils.hasText(codigo) || LegalDocument.TIPO_OTRO.equals(codigo.trim())) {
        continue;
      }
      String c = codigo.trim();
      out.add(new TipoDocumental(c, nombres.getOrDefault(c, legible(c))));
    }
    out.sort(Comparator.comparing(TipoDocumental::nombre, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(out);
  }

  /** Mayúsculas, sin tildes, guion bajo como espacio y espacios simples; vacío = {@code ALL}. */
  public static String normalizarCanton(String canton) {
    if (!StringUtils.hasText(canton)) {
      return CANTON_GENERAL;
    }
    String sinTildes =
        Normalizer.normalize(canton.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    String norm = sinTildes.replace('_', ' ').replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    return norm.isEmpty() ? CANTON_GENERAL : norm;
  }

  private static String legible(String codigo) {
    String texto = codigo.replace('_', ' ').toLowerCase(Locale.ROOT);
    return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
  }
}
