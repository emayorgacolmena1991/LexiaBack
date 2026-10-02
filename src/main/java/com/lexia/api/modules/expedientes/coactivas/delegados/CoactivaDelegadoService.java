package com.lexia.api.modules.expedientes.coactivas.delegados;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.CatalogosResponse;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoRequest;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.OficinaItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.OficinaRequest;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaDelegadoService {

  private final CoactivaDelegadoRepository delegados;
  private final CoactivaDelegadoOficinaRepository asignaciones;
  private final CoactivaOficinaRepository oficinas;
  private final AuthorizationService authorization;

  public CoactivaDelegadoService(
      CoactivaDelegadoRepository delegados,
      CoactivaDelegadoOficinaRepository asignaciones,
      CoactivaOficinaRepository oficinas,
      AuthorizationService authorization) {
    this.delegados = delegados;
    this.asignaciones = asignaciones;
    this.oficinas = oficinas;
    this.authorization = authorization;
  }

  @Transactional(readOnly = true)
  public CatalogosResponse catalogos() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    return new CatalogosResponse(listOficinas(tenantId), listDelegados(tenantId));
  }

  @Transactional(readOnly = true)
  public List<DelegadoItem> delegadosAdmin() {
    authorization.requirePermission(CoactivaPermisos.CONFIGURAR);
    return listDelegados(AuthContext.require().tenantId());
  }

  @Transactional(readOnly = true)
  public List<DelegadoItem> delegadosActivos() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    return listDelegados(AuthContext.require().tenantId()).stream().filter(DelegadoItem::activo).toList();
  }

  @Transactional(readOnly = true)
  public List<OficinaItem> oficinasAdmin() {
    authorization.requirePermission(CoactivaPermisos.CONFIGURAR);
    return listOficinas(AuthContext.require().tenantId());
  }

  @Transactional
  public DelegadoItem crearDelegado(DelegadoRequest request) {
    authorization.requirePermission(CoactivaPermisos.CONFIGURAR);
    AuthPrincipal principal = AuthContext.require();
    CoactivaDelegado delegado =
        CoactivaDelegado.create(principal.tenantId(), request.nombre().trim(), principal.userId());
    apply(delegado, request, principal.userId());
    delegados.save(delegado);
    reemplazarOficinas(principal.tenantId(), delegado.getId(), request.oficinas());
    return toItem(delegado, codigosDe(principal.tenantId(), delegado.getId()));
  }

  @Transactional
  public DelegadoItem actualizarDelegado(UUID id, DelegadoRequest request) {
    authorization.requirePermission(CoactivaPermisos.CONFIGURAR);
    AuthPrincipal principal = AuthContext.require();
    CoactivaDelegado delegado = require(principal.tenantId(), id);
    apply(delegado, request, principal.userId());
    if (request.oficinas() != null) {
      reemplazarOficinas(principal.tenantId(), id, request.oficinas());
    }
    return toItem(delegado, codigosDe(principal.tenantId(), id));
  }

  @Transactional
  public void eliminarDelegado(UUID id) {
    authorization.requirePermission(CoactivaPermisos.CONFIGURAR);
    AuthPrincipal principal = AuthContext.require();
    CoactivaDelegado delegado = require(principal.tenantId(), id);
    delegado.softDelete(principal.userId());
    asignaciones.deleteByDelegado(principal.tenantId(), id);
  }

  @Transactional
  public OficinaItem guardarOficina(OficinaRequest request) {
    authorization.requirePermission(CoactivaPermisos.CONFIGURAR);
    UUID tenantId = AuthContext.require().tenantId();
    String codigo = normalizarCodigoOficina(request.codigo());
    int sort = request.sortOrder() == null ? 99 : request.sortOrder();
    boolean activo = request.activo() == null || request.activo();
    CoactivaOficina oficina =
        oficinas
            .findByTenantIdAndCodigo(tenantId, codigo)
            .map(
                existing -> {
                  existing.update(request.nombre().trim(), CoactivaTexto.blankToNull(request.provincia()), activo, sort);
                  return existing;
                })
            .orElseGet(
                () ->
                    oficinas.save(
                        CoactivaOficina.create(
                            tenantId, codigo, request.nombre().trim(), CoactivaTexto.blankToNull(request.provincia()), sort)));
    return toItem(oficina);
  }

  /**
   * Delegado vigente para la oficina en la fecha. Si hay más de uno (p. ej. Guayaquil con 3
   * delegados) devuelve vacío: la asignación debe hacerse explícitamente.
   */
  @Transactional(readOnly = true)
  public Optional<CoactivaDelegado> resolverUnico(UUID tenantId, String oficinaCodigo, LocalDate fecha) {
    if (oficinaCodigo == null) {
      return Optional.empty();
    }
    List<UUID> ids =
        asignaciones.findByOficina(tenantId, oficinaCodigo).stream()
            .map(CoactivaDelegadoOficina::getDelegadoId)
            .toList();
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    List<CoactivaDelegado> vigentes =
        delegados.findByTenantIdAndIdIn(tenantId, ids).stream()
            .filter(d -> d.vigenteEn(fecha))
            .toList();
    return vigentes.size() == 1 ? Optional.of(vigentes.get(0)) : Optional.empty();
  }

  @Transactional(readOnly = true)
  public Map<UUID, CoactivaDelegado> porIds(UUID tenantId, Collection<UUID> ids) {
    Map<UUID, CoactivaDelegado> result = new HashMap<>();
    if (ids == null || ids.isEmpty()) {
      return result;
    }
    for (CoactivaDelegado delegado : delegados.findByTenantIdAndIdIn(tenantId, ids)) {
      result.put(delegado.getId(), delegado);
    }
    return result;
  }

  public Optional<CoactivaDelegado> buscar(UUID tenantId, UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    return delegados.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId);
  }

  public CoactivaDelegado require(UUID tenantId, UUID id) {
    return delegados
        .findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
        .orElseThrow(() -> ApiException.notFound("Delegado no encontrado."));
  }

  public boolean oficinaExiste(UUID tenantId, String codigo) {
    return codigo != null && oficinas.findByTenantIdAndCodigo(tenantId, codigo).isPresent();
  }

  /** Mapea un texto libre ("PORTOVIEJO", "Of. Manta", "BAHIA", "GYE") al código del catálogo. */
  @Transactional(readOnly = true)
  public String resolverCodigoOficina(UUID tenantId, String texto) {
    return resolverCodigo(texto, oficinas.findByTenantIdOrderBySortOrderAscNombreAsc(tenantId));
  }

  @Transactional(readOnly = true)
  public List<OficinaItem> oficinasActivas() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    return listOficinas(AuthContext.require().tenantId()).stream().filter(OficinaItem::activo).toList();
  }

  @Transactional(readOnly = true)
  public Map<String, CoactivaOficina> oficinasPorCodigo(UUID tenantId) {
    Map<String, CoactivaOficina> map = new HashMap<>();
    for (CoactivaOficina oficina : oficinas.findByTenantIdOrderBySortOrderAscNombreAsc(tenantId)) {
      map.put(oficina.getCodigo(), oficina);
    }
    return map;
  }

  /**
   * Coincidencia normalizada contra el catálogo. Exacta primero ({@code MANTA} = {@code Manta});
   * si no, la primera palabra ({@code BAHIA} = {@code Bahía de Caráquez}). Sin coincidencia, null:
   * no se inventa un código que el combo no puede seleccionar.
   */
  public static String resolverCodigo(String texto, List<CoactivaOficina> catalogo) {
    String clave = CoactivaTexto.claveNombre(texto);
    if (clave.isEmpty()) {
      return null;
    }
    if ("GYE".equals(clave) || "GUAYAS".equals(clave)) {
      clave = "GUAYAQUIL";
    }
    String porPrefijo = null;
    for (CoactivaOficina oficina : catalogo) {
      if (!oficina.isActivo()) {
        continue;
      }
      String codigo = CoactivaTexto.claveNombre(oficina.getCodigo().replace('_', ' '));
      String nombre = CoactivaTexto.claveNombre(oficina.getNombre());
      if (clave.equals(codigo) || clave.equals(nombre)) {
        return oficina.getCodigo();
      }
      if (porPrefijo == null && clave.length() >= 4
          && (nombre.startsWith(clave + " ") || codigo.startsWith(clave + " ")
              || contienePalabra(clave, nombre) || contienePalabra(clave, codigo))) {
        porPrefijo = oficina.getCodigo();
      }
    }
    return porPrefijo;
  }

  private static boolean contienePalabra(String frase, String palabra) {
    return palabra.length() >= 4 && (" " + frase + " ").contains(" " + palabra + " ");
  }

  private List<OficinaItem> listOficinas(UUID tenantId) {
    return oficinas.findByTenantIdOrderBySortOrderAscNombreAsc(tenantId).stream()
        .map(this::toItem)
        .toList();
  }

  private List<DelegadoItem> listDelegados(UUID tenantId) {
    Map<UUID, List<String>> porDelegado = new HashMap<>();
    for (CoactivaDelegadoOficina row : asignaciones.findByTenantId(tenantId)) {
      porDelegado.computeIfAbsent(row.getDelegadoId(), k -> new ArrayList<>()).add(row.getOficinaCodigo());
    }
    return delegados.findByTenantIdAndDeletedAtIsNullOrderByNombreAsc(tenantId).stream()
        .map(d -> toItem(d, porDelegado.getOrDefault(d.getId(), List.of())))
        .toList();
  }

  private List<String> codigosDe(UUID tenantId, UUID delegadoId) {
    return asignaciones.findByTenantId(tenantId).stream()
        .filter(row -> row.getDelegadoId().equals(delegadoId))
        .map(CoactivaDelegadoOficina::getOficinaCodigo)
        .sorted()
        .toList();
  }

  private void reemplazarOficinas(UUID tenantId, UUID delegadoId, List<String> codigos) {
    asignaciones.deleteByDelegado(tenantId, delegadoId);
    asignaciones.flush();
    if (codigos == null) {
      return;
    }
    Set<String> unicos = new LinkedHashSet<>();
    for (String codigo : codigos) {
      String normalized = normalizarCodigoOficina(codigo);
      if (normalized == null) {
        continue;
      }
      if (!oficinaExiste(tenantId, normalized)) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST, "COA_OFICINA_INVALIDA", "Oficina no configurada: " + normalized);
      }
      unicos.add(normalized);
    }
    for (String codigo : unicos) {
      asignaciones.save(CoactivaDelegadoOficina.create(tenantId, delegadoId, codigo));
    }
  }

  private void apply(CoactivaDelegado delegado, DelegadoRequest request, UUID userId) {
    if (request.vigenteDesde() != null
        && request.vigenteHasta() != null
        && request.vigenteHasta().isBefore(request.vigenteDesde())) {
      throw ApiException.badRequest("La vigencia hasta no puede ser anterior a la vigencia desde.");
    }
    delegado.update(
        request.nombre().trim(),
        CoactivaTexto.normalizarIdentificacion(request.identificacion()),
        CoactivaTexto.blankToNull(request.cargo()),
        CoactivaTexto.blankToNull(request.resolucionNumero()),
        request.resolucionFecha(),
        request.vigenteDesde(),
        request.vigenteHasta(),
        CoactivaTexto.blankToNull(request.email()),
        request.activo() == null || request.activo(),
        userId);
  }

  private static String normalizarCodigoOficina(String codigo) {
    String clean = CoactivaTexto.blankToNull(codigo);
    if (clean == null) {
      return null;
    }
    return CoactivaTexto.truncate(
        CoactivaTexto.sinTildes(clean).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"), 32);
  }

  private OficinaItem toItem(CoactivaOficina oficina) {
    return new OficinaItem(
        oficina.getId(),
        oficina.getCodigo(),
        oficina.getNombre(),
        oficina.getProvincia(),
        oficina.isActivo(),
        oficina.getSortOrder());
  }

  private DelegadoItem toItem(CoactivaDelegado delegado, List<String> oficinasAsignadas) {
    return new DelegadoItem(
        delegado.getId(),
        delegado.getNombre(),
        delegado.getIdentificacion(),
        delegado.getCargo(),
        delegado.getResolucionNumero(),
        delegado.getResolucionFecha(),
        delegado.getVigenteDesde(),
        delegado.getVigenteHasta(),
        delegado.getEmail(),
        delegado.isActivo(),
        delegado.vigenteEn(LocalDate.now()),
        oficinasAsignadas.stream().sorted().toList());
  }
}
