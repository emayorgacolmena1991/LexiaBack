package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoService;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.BandejaResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.CatalogosResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.EtapaItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ExpedienteResumen;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ExpedienteSelectorItem;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaBandejaService {

  private final CoactivaExpedienteRepository expedientes;
  private final CoactivaParticipanteRepository participantes;
  private final CoactivaArchivoRepository archivos;
  private final CoactivaDelegadoService delegados;
  private final CoactivaSemaforoService semaforo;
  private final AuthorizationService authorization;

  public CoactivaBandejaService(
      CoactivaExpedienteRepository expedientes,
      CoactivaParticipanteRepository participantes,
      CoactivaArchivoRepository archivos,
      CoactivaDelegadoService delegados,
      CoactivaSemaforoService semaforo,
      AuthorizationService authorization) {
    this.expedientes = expedientes;
    this.participantes = participantes;
    this.archivos = archivos;
    this.delegados = delegados;
    this.semaforo = semaforo;
    this.authorization = authorization;
  }

  @Transactional(readOnly = true)
  public CatalogosResponse catalogos() {
    var base = delegados.catalogos();
    List<EtapaItem> etapas =
        Arrays.stream(CoactivaEtapa.values())
            .map(e -> new EtapaItem(e.name(), e.stageCode(), e.label()))
            .toList();
    return new CatalogosResponse(base.oficinas(), base.delegados(), etapas);
  }

  @Transactional(readOnly = true)
  public BandejaResponse listar(Filtro filtro, int page, int size) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    Page<CoactivaExpediente> result =
        expedientes.findAll(
            specification(tenantId, filtro),
            PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
    List<CoactivaExpediente> rows = result.getContent();
    List<UUID> ids = rows.stream().map(CoactivaExpediente::getId).toList();

    Map<UUID, List<CoactivaParticipante>> porExpediente =
        ids.isEmpty()
            ? Map.of()
            : participantes.findByTenantIdAndExpedienteIdInAndDeletedAtIsNull(tenantId, ids).stream()
                .collect(Collectors.groupingBy(CoactivaParticipante::getExpedienteId));
    Map<UUID, Long> archivosPorExpediente = new HashMap<>();
    if (!ids.isEmpty()) {
      for (Object[] row : archivos.contarPorExpediente(tenantId, ids)) {
        archivosPorExpediente.put((UUID) row[0], (Long) row[1]);
      }
    }
    Map<UUID, CoactivaDelegado> delegadosPorId =
        delegados.porIds(
            tenantId,
            rows.stream().map(CoactivaExpediente::getDelegadoId).filter(d -> d != null).distinct().toList());

    LocalDate hoy = LocalDate.now();
    List<ExpedienteResumen> items = new ArrayList<>();
    for (CoactivaExpediente e : rows) {
      List<CoactivaParticipante> lista = new ArrayList<>(porExpediente.getOrDefault(e.getId(), List.of()));
      lista.sort(Comparator.comparingInt(CoactivaParticipante::getOrden));
      CoactivaParticipante deudor =
          lista.stream()
              .filter(p -> CoactivaParticipante.DEUDOR.equals(p.getRol()))
              .findFirst()
              .orElse(lista.isEmpty() ? null : lista.get(0));
      CoactivaDelegado delegado = e.getDelegadoId() == null ? null : delegadosPorId.get(e.getDelegadoId());
      items.add(
          new ExpedienteResumen(
              e.getId(),
              e.getCaseId(),
              e.getNroJuicio(),
              e.getNroOperacion(),
              e.getAnio(),
              e.getOficinaCodigo(),
              e.getDelegadoId(),
              delegado == null ? null : delegado.getNombre(),
              deudor == null ? null : deudor.getNombreCompleto(),
              deudor == null ? null : deudor.getIdentificacion(),
              lista.size(),
              e.getEstadoOperativo(),
              e.getEtapaReportada(),
              e.getEtapaReportadaTexto(),
              e.getEtapaVerificada(),
              e.getEtapaVerificada() == null ? null : CoactivaExpedienteService.etapaLabel(e.getEtapaVerificada()),
              e.getSemaforo(),
              e.getSemaforoMotivo(),
              semaforo.siguienteAccion(e, delegado != null && delegado.vigenteEn(hoy)),
              archivosPorExpediente.getOrDefault(e.getId(), 0L),
              e.isSuspendido(),
              e.getFechaUltimaActuacion(),
              e.getUpdatedAt()));
    }

    return new BandejaResponse(
        items,
        safePage,
        safeSize,
        result.getTotalElements(),
        toMap(expedientes.contarPorSemaforo(tenantId)),
        toMap(expedientes.contarPorEtapa(tenantId)),
        toMap(expedientes.contarPorEstado(tenantId)));
  }

  private static Specification<CoactivaExpediente> specification(UUID tenantId, Filtro filtro) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(root.get("tenantId"), tenantId));
      predicates.add(cb.isNull(root.get("deletedAt")));
      if (filtro.oficina() != null) {
        predicates.add(cb.equal(root.get("oficinaCodigo"), filtro.oficina()));
      }
      if (filtro.delegadoId() != null) {
        predicates.add(cb.equal(root.get("delegadoId"), filtro.delegadoId()));
      }
      if (filtro.etapa() != null) {
        if ("SIN_ETAPA".equals(filtro.etapa())) {
          predicates.add(cb.isNull(root.get("etapaVerificada")));
        } else {
          predicates.add(cb.equal(root.get("etapaVerificada"), filtro.etapa()));
        }
      }
      if (filtro.semaforo() != null) {
        predicates.add(cb.equal(root.get("semaforo"), filtro.semaforo()));
      }
      if (filtro.estadoOperativo() != null) {
        predicates.add(cb.equal(root.get("estadoOperativo"), filtro.estadoOperativo()));
      }
      String q = CoactivaTexto.blankToNull(filtro.q());
      if (q != null) {
        String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
        Subquery<UUID> sub = query.subquery(UUID.class);
        Root<CoactivaParticipante> p = sub.from(CoactivaParticipante.class);
        sub.select(p.get("expedienteId"))
            .where(
                cb.equal(p.get("tenantId"), tenantId),
                cb.isNull(p.get("deletedAt")),
                cb.or(
                    cb.like(cb.lower(p.get("nombreCompleto")), like),
                    cb.like(p.get("identificacion"), "%" + q + "%")));
        predicates.add(
            cb.or(
                cb.like(cb.lower(root.get("nroJuicio")), like),
                cb.like(cb.lower(root.get("nroOperacion")), like),
                root.get("id").in(sub)));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  @Transactional(readOnly = true)
  public List<ExpedienteSelectorItem> selector(String query, int limit, boolean soloSinDocumento) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    int safeLimit = Math.min(Math.max(limit, 1), 50);
    String q = CoactivaTexto.blankToNull(query);
    List<CoactivaExpediente> sinDocumento = buscarSelector(tenantId, q, safeLimit, true);
    List<CoactivaExpediente> conDocumento = List.of();
    if (!soloSinDocumento && sinDocumento.size() < safeLimit) {
      conDocumento = buscarSelector(tenantId, q, safeLimit - sinDocumento.size(), false);
    }
    return aSelector(tenantId, sinDocumento, conDocumento);
  }

  private List<CoactivaExpediente> buscarSelector(
      UUID tenantId, String query, int limit, boolean sinDocumento) {
    return expedientes
        .findAll(
            selectorSpec(tenantId, query, sinDocumento),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
        .getContent();
  }

  private List<ExpedienteSelectorItem> aSelector(
      UUID tenantId, List<CoactivaExpediente> sinDocumento, List<CoactivaExpediente> conDocumento) {
    List<CoactivaExpediente> rows = new ArrayList<>(sinDocumento);
    rows.addAll(conDocumento);
    if (rows.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = rows.stream().map(CoactivaExpediente::getId).toList();
    Map<UUID, List<CoactivaParticipante>> porExpediente =
        participantes.findByTenantIdAndExpedienteIdInAndDeletedAtIsNull(tenantId, ids).stream()
            .collect(Collectors.groupingBy(CoactivaParticipante::getExpedienteId));
    Map<String, String> oficinas = new HashMap<>();
    for (var oficina : delegados.catalogos().oficinas()) {
      oficinas.put(oficina.codigo(), oficina.nombre());
    }
    Set<UUID> conPdf = conDocumento.stream().map(CoactivaExpediente::getId).collect(Collectors.toSet());
    List<ExpedienteSelectorItem> items = new ArrayList<>();
    for (CoactivaExpediente expediente : rows) {
      List<CoactivaParticipante> lista =
          new ArrayList<>(porExpediente.getOrDefault(expediente.getId(), List.of()));
      lista.sort(Comparator.comparingInt(CoactivaParticipante::getOrden));
      CoactivaParticipante deudor =
          lista.stream().filter(p -> CoactivaParticipante.DEUDOR.equals(p.getRol())).findFirst().orElse(null);
      String etapa = expediente.getEtapaVerificada();
      if (etapa == null || etapa.isBlank()) {
        etapa = expediente.getEtapaReportada();
      }
      String codigo = expediente.getOficinaCodigo();
      items.add(
          new ExpedienteSelectorItem(
              expediente.getId(),
              expediente.getNroJuicio(),
              expediente.getNroOperacion(),
              deudor == null ? null : deudor.getNombreCompleto(),
              deudor == null ? null : deudor.getIdentificacion(),
              codigo == null ? null : oficinas.getOrDefault(codigo, codigo),
              etapa,
              conPdf.contains(expediente.getId())));
    }
    return items;
  }

  private static Specification<CoactivaExpediente> selectorSpec(
      UUID tenantId, String query, boolean sinDocumento) {
    return (root, cq, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(root.get("tenantId"), tenantId));
      predicates.add(cb.isNull(root.get("deletedAt")));
      Subquery<UUID> docs = cq.subquery(UUID.class);
      Root<CoactivaArchivo> archivo = docs.from(CoactivaArchivo.class);
      docs.select(archivo.get("expedienteId"))
          .where(
              cb.equal(archivo.get("tenantId"), tenantId),
              cb.isNull(archivo.get("deletedAt")),
              cb.isNotNull(archivo.get("expedienteId")),
              archivo
                  .get("tipo")
                  .in(CoactivaArchivo.EXPEDIENTE_ESCANEADO, CoactivaArchivo.EXPEDIENTE_UNIFICADO));
      predicates.add(sinDocumento ? cb.not(root.get("id").in(docs)) : root.get("id").in(docs));
      if (query != null) {
        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
        Subquery<UUID> sub = cq.subquery(UUID.class);
        Root<CoactivaParticipante> participante = sub.from(CoactivaParticipante.class);
        sub.select(participante.get("expedienteId"))
            .where(
                cb.equal(participante.get("tenantId"), tenantId),
                cb.isNull(participante.get("deletedAt")),
                cb.or(
                    cb.like(cb.lower(participante.get("nombreCompleto")), like),
                    cb.like(participante.get("identificacion"), "%" + query + "%")));
        predicates.add(
            cb.or(
                cb.like(cb.lower(root.get("nroJuicio")), like),
                cb.like(cb.lower(cb.coalesce(root.get("nroOperacion"), "")), like),
                root.get("id").in(sub)));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  private static Map<String, Long> toMap(List<Object[]> rows) {
    Map<String, Long> map = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String key = row[0] == null ? "SIN_ETAPA" : row[0].toString();
      map.merge(key, (Long) row[1], Long::sum);
    }
    return map;
  }

  public record Filtro(
      String oficina, UUID delegadoId, String etapa, String semaforo, String estadoOperativo, String q) {

    public Filtro {
      oficina = upper(oficina);
      etapa = etapa == null ? null : CoactivaEtapa.parse(etapa).map(Enum::name).orElse(upper(etapa));
      semaforo = upper(semaforo);
      estadoOperativo = upper(estadoOperativo);
    }

    private static String upper(String value) {
      String clean = CoactivaTexto.blankToNull(value);
      return clean == null ? null : clean.toUpperCase(Locale.ROOT);
    }
  }
}
