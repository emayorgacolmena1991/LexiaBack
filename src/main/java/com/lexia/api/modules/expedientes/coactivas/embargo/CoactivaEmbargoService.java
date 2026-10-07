package com.lexia.api.modules.expedientes.coactivas.embargo;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficina;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficinaRepository;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.Datos;
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
 * Data 2: un solo lote EN_PREPARACION por tenant, compartido por todos los usuarios. El corte del
 * jueves 12:00 es informativo; el lote solo se cierra cuando alguien lo marca como ENTREGADO.
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
  private final ZoneId zona;

  public CoactivaEmbargoService(
      AuthorizationService authorization,
      CoactivaExpedienteService expedientes,
      CoactivaEmbargoLoteRepository lotes,
      CoactivaEmbargoRegistroRepository registros,
      CoactivaPlantillaDataMapper mapper,
      CoactivaActaRepository actas,
      CoactivaOficinaRepository oficinas,
      @Value("${lexia.coactivas.plantillas.zona-horaria:America/Guayaquil}") String zonaHoraria) {
    this.authorization = authorization;
    this.expedientes = expedientes;
    this.lotes = lotes;
    this.registros = registros;
    this.mapper = mapper;
    this.actas = actas;
    this.oficinas = oficinas;
    this.zona = ZoneId.of(zonaHoraria);
  }

  @Transactional(readOnly = true)
  public ExpedienteResponse expediente(UUID expedienteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    Instant ahora = Instant.now();
    Optional<CoactivaEmbargoLote> lote = lotes.activo(tenantId);
    RegistroItem registro =
        lote.flatMap(l -> registros.findByTenantIdAndLoteIdAndExpedienteId(tenantId, l.getId(), expedienteId))
            .map(CoactivaEmbargoService::item)
            .orElse(null);
    Map<String, Object> valores = mapper.mapear(expediente).valores();
    return new ExpedienteResponse(
        resumen(tenantId, lote, ahora),
        ahora,
        registro != null || contextoEmbargo(expediente),
        registro,
        registro == null ? propuesta(expediente, valores) : null,
        primero(valores, "monto_retencion", "monto_embargo_total", "monto_embargo_1"));
  }

  @Transactional(readOnly = true)
  public RegistrosResponse registros() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    return registros(tenantId, lotes.activo(tenantId));
  }

  @Transactional(readOnly = true)
  public RegistrosResponse registros(UUID loteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    return registros(tenantId, Optional.of(lote(tenantId, loteId)));
  }

  /** Más reciente primero. */
  @Transactional(readOnly = true)
  public List<LoteEntregado> entregados() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    // ponytail: un count por lote (≈1 lote/semana); si el historial crece mucho, agrupar en una sola consulta o paginar.
    return lotes.findByTenantIdAndEstadoOrderByEntregadoAtDescNumeroDesc(tenantId, CoactivaEmbargoLote.ENTREGADO).stream()
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

  private RegistrosResponse registros(UUID tenantId, Optional<CoactivaEmbargoLote> lote) {
    Instant ahora = Instant.now();
    return new RegistrosResponse(resumen(tenantId, lote, ahora), ahora, filas(tenantId, lote));
  }

  private CoactivaEmbargoLote lote(UUID tenantId, UUID loteId) {
    return lotes
        .findByIdAndTenantId(loteId, tenantId)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "EMBARGO_LOTE_NO_ENCONTRADO", "No existe ese Data 2."));
  }

  @Transactional
  public RegistroItem guardar(UUID expedienteId, GuardarRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    CoactivaEmbargoLote lote = loteParaEscribir(tenantId, principal.userId());
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

  @Transactional(readOnly = true)
  public Descarga descargar() {
    authorization.requirePermission(CoactivaPermisos.GENERAR_DOCUMENTOS);
    UUID tenantId = AuthContext.require().tenantId();
    return descargar(tenantId, lotes.activo(tenantId));
  }

  @Transactional(readOnly = true)
  public Descarga descargar(UUID loteId) {
    authorization.requirePermission(CoactivaPermisos.GENERAR_DOCUMENTOS);
    UUID tenantId = AuthContext.require().tenantId();
    return descargar(tenantId, Optional.of(lote(tenantId, loteId)));
  }

  private Descarga descargar(UUID tenantId, Optional<CoactivaEmbargoLote> lote) {
    LoteResumen resumen = resumen(tenantId, lote, Instant.now());
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
            .bloquearActivo(tenantId)
            .filter(l -> l.getId().equals(loteId))
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "EMBARGO_LOTE_CAMBIADO",
                        "Ese Data 2 ya no está en preparación. Recarga para ver el lote actual."));
    long total = registros.countByTenantIdAndLoteId(tenantId, lote.getId());
    lote.entregar(principal.userId());
    return new LoteResumen(lote.getId(), lote.getNumero(), lote.getEstado(), lote.getFechaCorte(), total, false);
  }

  /** Bloquea la fila del lote activo (creándolo si hace falta) hasta el commit. */
  private CoactivaEmbargoLote loteParaEscribir(UUID tenantId, UUID userId) {
    Optional<CoactivaEmbargoLote> activo = lotes.bloquearActivo(tenantId);
    if (activo.isPresent()) {
      return activo.get();
    }
    Instant corte = corteNuevoLote(Instant.now(), lotes.ultimoCorte(tenantId).orElse(null), zona);
    lotes.abrir(UUID.randomUUID(), tenantId, lotes.ultimoNumero(tenantId) + 1, corte, userId);
    return lotes.bloquearActivo(tenantId).orElseThrow();
  }

  private LoteResumen resumen(UUID tenantId, Optional<CoactivaEmbargoLote> lote, Instant ahora) {
    if (lote.isEmpty()) {
      Instant corte = corteNuevoLote(ahora, lotes.ultimoCorte(tenantId).orElse(null), zona);
      return new LoteResumen(
          null, lotes.ultimoNumero(tenantId) + 1, CoactivaEmbargoLote.EN_PREPARACION, corte, 0, false);
    }
    CoactivaEmbargoLote l = lote.get();
    return new LoteResumen(
        l.getId(),
        l.getNumero(),
        l.getEstado(),
        l.getFechaCorte(),
        registros.countByTenantIdAndLoteId(tenantId, l.getId()),
        CoactivaEmbargoLote.EN_PREPARACION.equals(l.getEstado()) && ahora.isAfter(l.getFechaCorte()));
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
