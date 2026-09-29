package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoService;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.BandejaResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.CatalogosResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.EtapaItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ExpedienteResumen;
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
