package com.lexia.api.modules.expedientes.coactivas.embargo;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoService;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficina;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficinaRepository;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.Datos;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.DelegadoResumen;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.ExpedienteResponse;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.GuardarRequest;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.LoteEntregado;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.LoteResumen;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.RegistroItem;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.RegistrosResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaActa;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaActaRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data 2: un lote EN_PREPARACION por tenant y delegado ({@code coactiva_delegado.id}). El corte del
 * jueves 12:00 es informativo y propio de cada delegado; el lote solo se cierra cuando alguien lo
 * marca como ENTREGADO, sin tocar el lote activo de otro delegado.
 */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaEmbargoService {

  static final DayOfWeek DIA_CORTE = DayOfWeek.THURSDAY;
  static final LocalTime HORA_CORTE = LocalTime.NOON;
  private static final long SIN_VERSION = -1L;

  private final AuthorizationService authorization;
  private final CoactivaExpedienteService expedientes;
  private final CoactivaEmbargoLoteRepository lotes;
  private final CoactivaEmbargoRegistroRepository registros;
  private final CoactivaPlantillaDataMapper mapper;
  private final CoactivaActaRepository actas;
  private final CoactivaOficinaRepository oficinas;
  private final CoactivaDelegadoService delegados;
  private final ZoneId zona;

  public CoactivaEmbargoService(
      AuthorizationService authorization,
      CoactivaExpedienteService expedientes,
      CoactivaEmbargoLoteRepository lotes,
      CoactivaEmbargoRegistroRepository registros,
      CoactivaPlantillaDataMapper mapper,
      CoactivaActaRepository actas,
      CoactivaOficinaRepository oficinas,
      CoactivaDelegadoService delegados,
      @Value("${lexia.coactivas.plantillas.zona-horaria:America/Guayaquil}") String zonaHoraria) {
    this.authorization = authorization;
    this.expedientes = expedientes;
    this.lotes = lotes;
    this.registros = registros;
    this.mapper = mapper;
    this.actas = actas;
    this.oficinas = oficinas;
    this.delegados = delegados;
    this.zona = ZoneId.of(zonaHoraria);
  }

  /** Identificador estable del delegado del expediente y el nombre para mostrar. */
  public record DelegadoRef(UUID delegadoId, String delegadoNombre) {}

  @Transactional(readOnly = true)
  public ExpedienteResponse expediente(UUID expedienteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    DelegadoRef delegado = resolverDelegado(tenantId, expediente);
    Instant ahora = Instant.now();
    Optional<CoactivaEmbargoLote> lote = lotes.activo(tenantId, delegado.delegadoId());
    RegistroItem registro =
        lote.flatMap(l -> registros.findByTenantIdAndLoteIdAndExpedienteId(tenantId, l.getId(), expedienteId))
            .map(CoactivaEmbargoService::item)
            .orElse(null);
    Map<String, Object> valores = mapper.mapear(expediente).valores();
    return new ExpedienteResponse(
        resumen(tenantId, lote, ahora, delegado),
        ahora,
        registro != null || contextoEmbargo(expediente),
        registro,
        registro == null ? propuesta(expediente, valores) : null,
        primero(valores, "monto_retencion", "monto_embargo_total", "monto_embargo_1"),
        delegado.delegadoId(),
        delegado.delegadoNombre());
  }

  /** Sin {@code loteId}: el lote en preparación del delegado. */
  @Transactional(readOnly = true)
  public RegistrosResponse registros(UUID expedienteId, UUID delegadoId, UUID loteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    DelegadoRef delegado = delegado(tenantId, expedienteId, delegadoId);
    return registros(tenantId, lote(tenantId, loteId, delegado), delegado);
  }

  /** Delegados activos (o con lote abierto) y el avance de su lote en preparación. */
  @Transactional(readOnly = true)
  public List<DelegadoResumen> delegados() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    Instant ahora = Instant.now();
    List<DelegadoResumen> resultado = new ArrayList<>();
    // ponytail: 3 consultas por delegado (lote, filas, conteo); si llegan a decenas, agrupar en una sola consulta.
    for (DelegadoItem d : delegados.catalogos().delegados()) {
      DelegadoRef ref = new DelegadoRef(d.id(), d.nombre());
      Optional<CoactivaEmbargoLote> lote = lotes.activo(tenantId, d.id());
      if (lote.isEmpty() && !d.activo()) {
        continue;
      }
      List<RegistroItem> filas = filas(tenantId, lote);
      long completos = filas.stream().filter(r -> completo(r.datos())).count();
      resultado.add(
          new DelegadoResumen(
              d.id(),
              d.nombre(),
              lote.isPresent() ? resumen(tenantId, lote, ahora, ref) : null,
              completos,
              filas.size() - completos));
    }
    return resultado;
  }

  /** Historial del delegado (explícito o el del expediente). Más reciente primero. */
  @Transactional(readOnly = true)
  public List<LoteEntregado> entregados(UUID expedienteId, UUID delegadoId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    DelegadoRef delegado = delegado(tenantId, expedienteId, delegadoId);
    // ponytail: un count por lote (≈1 lote/semana por delegado); si el historial crece mucho, agrupar en una sola consulta o paginar.
    return lotes
        .findByTenantIdAndDelegadoIdAndEstadoOrderByEntregadoAtDescNumeroDesc(
            tenantId, delegado.delegadoId(), CoactivaEmbargoLote.ENTREGADO)
        .stream()
        .map(
            l ->
                new LoteEntregado(
                    l.getId(),
                    l.getNumero(),
                    l.getFechaCorte(),
                    l.getEntregadoAt(),
                    registros.countByTenantIdAndLoteId(tenantId, l.getId())))
        .toList();
  }

  private RegistrosResponse registros(UUID tenantId, Optional<CoactivaEmbargoLote> lote, DelegadoRef delegado) {
    Instant ahora = Instant.now();
    return new RegistrosResponse(resumen(tenantId, lote, ahora, delegado), ahora, filas(tenantId, lote));
  }

  private Optional<CoactivaEmbargoLote> lote(UUID tenantId, UUID loteId, DelegadoRef delegado) {
    return loteId == null
        ? lotes.activo(tenantId, delegado.delegadoId())
        : Optional.of(loteDelDelegado(tenantId, loteId, delegado));
  }

  private CoactivaEmbargoLote loteDelDelegado(UUID tenantId, UUID loteId, DelegadoRef delegado) {
    CoactivaEmbargoLote lote =
        lotes
            .findByIdAndTenantId(loteId, tenantId)
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "EMBARGO_LOTE_NO_ENCONTRADO", "No existe ese Data 2."));
    if (lote.getDelegadoId() == null || !lote.getDelegadoId().equals(delegado.delegadoId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EMBARGO_LOTE_NO_ENCONTRADO", "No existe ese Data 2.");
    }
    return lote;
  }

  @Transactional
  public RegistroItem guardar(UUID expedienteId, GuardarRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    DelegadoRef delegado = resolverDelegado(tenantId, expediente);
    CoactivaEmbargoLote lote = loteParaEscribir(tenantId, delegado, principal.userId());
    if (request.loteId() != null && !request.loteId().equals(lote.getId())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "EMBARGO_LOTE_CAMBIADO",
          "El Data 2 que estabas editando ya fue entregado. Recarga para trabajar sobre el lote actual.");
    }
    boolean existe =
        registros.findByTenantIdAndLoteIdAndExpedienteId(tenantId, lote.getId(), expedienteId).isPresent();
    if (!existe && !contextoEmbargo(expediente)) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "EMBARGO_CONTEXTO",
          "El expediente no está en una actuación de embargo; no se puede agregar a Data 2.");
    }
    Datos d = limpiar(request.datos());
    int filas =
        registros.upsert(
            UUID.randomUUID(),
            tenantId,
            lote.getId(),
            expedienteId,
            d.juzgado(),
            d.oficinaOrigenCredito(),
            d.operacion(),
            d.numeroJuicio(),
            d.nombreCoactivado(),
            d.nombreTitularOperacion(),
            d.valorTransferido(),
            d.fechaProceso(),
            d.nombreDerSac(),
            d.numeroOficioRespuesta(),
            d.numeroDocumento(),
            principal.userId(),
            request.rowVersion() == null ? SIN_VERSION : request.rowVersion());
    if (filas == 0) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "EMBARGO_REGISTRO_MODIFICADO",
          "Otro usuario guardó este registro mientras lo editabas. Recarga para ver sus cambios antes de guardar.");
    }
    return registros
        .findByTenantIdAndLoteIdAndExpedienteId(tenantId, lote.getId(), expedienteId)
        .map(CoactivaEmbargoService::item)
        .orElseThrow();
  }

  public record Descarga(String nombre, byte[] bytes) {}

  /** Sin {@code loteId}: el lote en preparación del delegado. */
  @Transactional(readOnly = true)
  public Descarga descargar(UUID expedienteId, UUID delegadoId, UUID loteId) {
    authorization.requirePermission(CoactivaPermisos.GENERAR_DOCUMENTOS);
    UUID tenantId = AuthContext.require().tenantId();
    DelegadoRef delegado = delegado(tenantId, expedienteId, delegadoId);
    return descargar(tenantId, lote(tenantId, loteId, delegado), delegado);
  }

  private Descarga descargar(UUID tenantId, Optional<CoactivaEmbargoLote> lote, DelegadoRef delegado) {
    LoteResumen resumen = resumen(tenantId, lote, Instant.now(), delegado);
    List<Datos> datos = filas(tenantId, lote).stream().map(RegistroItem::datos).toList();
    try (InputStream plantilla = new ClassPathResource(CoactivaEmbargoExcel.PLANTILLA).getInputStream()) {
      byte[] bytes = CoactivaEmbargoExcel.generar(plantilla, datos);
      String corte = DateTimeFormatter.ISO_LOCAL_DATE.format(resumen.fechaCorte().atZone(zona));
      return new Descarga("DATA 2 - Lote %03d - corte %s.xlsx".formatted(resumen.numero(), corte), bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo generar Data 2", e);
    }
  }

  @Transactional
  public LoteResumen entregar(UUID loteId) {
    authorization.requirePermission(CoactivaPermisos.GENERAR_DOCUMENTOS);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaEmbargoLote lote =
        lotes
            .bloquear(loteId, tenantId)
            .filter(l -> CoactivaEmbargoLote.EN_PREPARACION.equals(l.getEstado()))
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "EMBARGO_LOTE_CAMBIADO",
                        "Ese Data 2 ya no está en preparación. Recarga para ver el lote actual."));
    long total = registros.countByTenantIdAndLoteId(tenantId, lote.getId());
    lote.entregar(principal.userId());
    return new LoteResumen(
        lote.getId(),
        lote.getNumero(),
        lote.getEstado(),
        lote.getFechaCorte(),
        total,
        false,
        lote.getDelegadoId(),
        lote.getDelegadoNombre());
  }

  /** Bloquea la fila del lote activo de este delegado (creándolo si hace falta) hasta el commit. */
  private CoactivaEmbargoLote loteParaEscribir(UUID tenantId, DelegadoRef delegado, UUID userId) {
    Optional<CoactivaEmbargoLote> activo = lotes.bloquearActivo(tenantId, delegado.delegadoId());
    if (activo.isPresent()) {
      return activo.get();
    }
    Instant corte =
        corteNuevoLote(Instant.now(), lotes.ultimoCorte(tenantId, delegado.delegadoId()).orElse(null), zona);
    lotes.abrir(
        UUID.randomUUID(),
        tenantId,
        delegado.delegadoId(),
        delegado.delegadoNombre(),
        lotes.ultimoNumero(tenantId) + 1,
        corte,
        userId);
    return lotes.bloquearActivo(tenantId, delegado.delegadoId()).orElseThrow();
  }

  /**
   * Delegado del expediente. Primero el {@code delegado_id} persistido; si no hay, el único delegado
   * vigente de la oficina. No usa el nombre de funcionario del OCR: es texto y no identifica el lote.
   */
  DelegadoRef resolverDelegado(UUID tenantId, CoactivaExpediente expediente) {
    if (expediente.getDelegadoId() != null) {
      return delegados
          .buscar(tenantId, expediente.getDelegadoId())
          .map(CoactivaEmbargoService::ref)
          .orElseThrow(CoactivaEmbargoService::delegadoNoConfigurado);
    }
    return delegados
        .resolverUnico(tenantId, expediente.getOficinaCodigo(), LocalDate.now(zona))
        .map(CoactivaEmbargoService::ref)
        .orElseThrow(CoactivaEmbargoService::delegadoNoConfigurado);
  }

  /** {@code delegadoId} explícito (vista por delegado) o el delegado del expediente. */
  private DelegadoRef delegado(UUID tenantId, UUID expedienteId, UUID delegadoId) {
    if (delegadoId != null) {
      return delegados
          .buscar(tenantId, delegadoId)
          .map(CoactivaEmbargoService::ref)
          .orElseThrow(() -> ApiException.notFound("Delegado no encontrado."));
    }
    if (expedienteId == null) {
      throw ApiException.badRequest("Indica el expediente o el delegado.");
    }
    return resolverDelegado(tenantId, expedientes.require(tenantId, expedienteId));
  }

  /** Mismo criterio que {@code embargoFaltantes} del front: las 11 columnas con valor. */
  static boolean completo(Datos d) {
    return Stream.of(
            d.juzgado(),
            d.oficinaOrigenCredito(),
            d.operacion(),
            d.numeroJuicio(),
            d.nombreCoactivado(),
            d.nombreTitularOperacion(),
            d.valorTransferido(),
            d.fechaProceso(),
            d.nombreDerSac(),
            d.numeroOficioRespuesta(),
            d.numeroDocumento())
        .allMatch(v -> v != null && !v.toString().isBlank());
  }

  private static DelegadoRef ref(CoactivaDelegado delegado) {
    return new DelegadoRef(delegado.getId(), delegado.getNombre());
  }

  private static ApiException delegadoNoConfigurado() {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DELEGADO_NO_CONFIGURADO",
        "El expediente no tiene un delegado asignado. Asígnalo antes de usar Data 2.");
  }

  private LoteResumen resumen(
      UUID tenantId, Optional<CoactivaEmbargoLote> lote, Instant ahora, DelegadoRef delegado) {
    if (lote.isEmpty()) {
      Instant corte =
          corteNuevoLote(ahora, lotes.ultimoCorte(tenantId, delegado.delegadoId()).orElse(null), zona);
      return new LoteResumen(
          null,
          lotes.ultimoNumero(tenantId) + 1,
          CoactivaEmbargoLote.EN_PREPARACION,
          corte,
          0,
          false,
          delegado.delegadoId(),
          delegado.delegadoNombre());
    }
    CoactivaEmbargoLote l = lote.get();
    return new LoteResumen(
        l.getId(),
        l.getNumero(),
        l.getEstado(),
        l.getFechaCorte(),
        registros.countByTenantIdAndLoteId(tenantId, l.getId()),
        CoactivaEmbargoLote.EN_PREPARACION.equals(l.getEstado()) && ahora.isAfter(l.getFechaCorte()),
        l.getDelegadoId() != null ? l.getDelegadoId() : delegado.delegadoId(),
        l.getDelegadoNombre() != null ? l.getDelegadoNombre() : delegado.delegadoNombre());
  }

  /** Única fuente de filas: la usan tanto "Ver Excel" como la descarga. */
  private List<RegistroItem> filas(UUID tenantId, Optional<CoactivaEmbargoLote> lote) {
    return lote.map(
            l ->
                registros.findByTenantIdAndLoteIdOrderByCreatedAtAscIdAsc(tenantId, l.getId()).stream()
                    .map(CoactivaEmbargoService::item)
                    .toList())
        .orElse(List.of());
  }

  /**
   * Primer jueves 12:00 estrictamente posterior a {@code max(ahora, corteAnterior)}. Si el lote
   * anterior se entregó antes de su corte (jueves 11:00), el nuevo corta el jueves siguiente.
   */
  static Instant corteNuevoLote(Instant ahora, Instant corteAnterior, ZoneId zona) {
    Instant desde = corteAnterior != null && corteAnterior.isAfter(ahora) ? corteAnterior : ahora;
    ZonedDateTime local = desde.atZone(zona);
    ZonedDateTime corte =
        local.toLocalDate().with(TemporalAdjusters.nextOrSame(DIA_CORTE)).atTime(HORA_CORTE).atZone(zona);
    if (!corte.toInstant().isAfter(desde)) {
      corte = corte.plusWeeks(1);
    }
    return corte.toInstant();
  }

  /**
   * Contexto de embargo según datos reales del expediente, no solo la pestaña visible: etapa
   * verificada, reportada en el acta o sugerida por la IA. No exige que esté confirmada.
   */
  static boolean contextoEmbargo(CoactivaExpediente e) {
    String embargo = CoactivaEtapa.EMBARGO.name();
    return Stream.of(e.getEtapaVerificada(), e.getEtapaReportada(), e.getEtapaSugeridaIa())
        .anyMatch(embargo::equals);
  }

  /**
   * Los datos estructurados (UEC del acta, oficina del catálogo) priman sobre la IA. VALOR
   * TRANSFERIDO, N° OFICIO DE RESPUESTA y N° DOCUMENTO quedan vacíos: los ingresa el usuario.
   */
  private Datos propuesta(CoactivaExpediente expediente, Map<String, Object> v) {
    String uec =
        expediente.getActaEntregaId() == null
            ? null
            : actas
                .findByIdAndTenantIdAndDeletedAtIsNull(expediente.getActaEntregaId(), expediente.getTenantId())
                .map(CoactivaActa::getUecNombre)
                .map(CoactivaTexto::blankToNull)
                .orElse(null);
    String oficina =
        expediente.getOficinaCodigo() == null
            ? null
            : oficinas
                .findByTenantIdAndCodigo(expediente.getTenantId(), expediente.getOficinaCodigo())
                .map(CoactivaOficina::getNombre)
                .map(CoactivaTexto::blankToNull)
                .orElse(null);
    return new Datos(
        firstNonNull(uec, primero(v, "juzgado"), primero(v, "unidad_ejecucion_coactiva")),
        firstNonNull(oficina, primero(v, "agencia")),
        primero(v, "numero_operacion"),
        primero(v, "numero_juicio_coactivo"),
        primero(v, "nombre_coactivado", "nombre_deudor_principal"),
        primero(v, "nombre_deudor_principal"),
        null,
        LocalDate.now(zona),
        primero(v, "nombre_funcionario_coactiva"),
        null,
        null);
  }

  private static String primero(Map<String, Object> valores, String... claves) {
    for (String clave : claves) {
      Object v = valores.get(clave);
      String s = v == null ? null : CoactivaTexto.blankToNull(v.toString());
      if (s != null) {
        return s;
      }
    }
    return null;
  }

  private static String firstNonNull(String... valores) {
    return Stream.of(valores).filter(s -> s != null).findFirst().orElse(null);
  }

  private static Datos limpiar(Datos d) {
    return new Datos(
        CoactivaTexto.blankToNull(d.juzgado()),
        CoactivaTexto.blankToNull(d.oficinaOrigenCredito()),
        CoactivaTexto.blankToNull(d.operacion()),
        CoactivaTexto.blankToNull(d.numeroJuicio()),
        CoactivaTexto.blankToNull(d.nombreCoactivado()),
        CoactivaTexto.blankToNull(d.nombreTitularOperacion()),
        d.valorTransferido(),
        d.fechaProceso(),
        CoactivaTexto.blankToNull(d.nombreDerSac()),
        CoactivaTexto.blankToNull(d.numeroOficioRespuesta()),
        CoactivaTexto.blankToNull(d.numeroDocumento()));
  }

  private static RegistroItem item(CoactivaEmbargoRegistro r) {
    return new RegistroItem(
        r.getId(),
        r.getLoteId(),
        r.getExpedienteId(),
        r.getRowVersion(),
        new Datos(
            r.getJuzgado(),
            r.getOficinaOrigenCredito(),
            r.getOperacion(),
            r.getNumeroJuicio(),
            r.getNombreCoactivado(),
            r.getNombreTitularOperacion(),
            r.getValorTransferido(),
            r.getFechaProceso(),
            r.getNombreDerSac(),
            r.getNumeroOficioRespuesta(),
            r.getNumeroDocumento()),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
