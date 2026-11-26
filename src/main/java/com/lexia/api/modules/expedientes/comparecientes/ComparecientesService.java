package com.lexia.api.modules.expedientes.comparecientes;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.comparecientes.RolCompareciente.Parte;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.ContextoExpediente;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Persona;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Comparecientes de un borrador o expediente: la IA los crea tras el análisis (listas sin tope) y el
 * abogado los edita, agrega o elimina. Si el abogado tocó alguno, la IA ya no reemplaza la lista.
 * Cada persona puede tener documentos ligados (cédula, papeleta, nombramiento, poder).
 */
@Service
public class ComparecientesService {

  private static final Logger LOG = LoggerFactory.getLogger(ComparecientesService.class);
  /** Las plantillas actuales tienen tags para 2 personas por parte. */
  public static final int MAX_EN_PLANTILLA = 2;

  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final ComparecienteRepository repo;
  private final CargaDocumentoService carga;

  public ComparecientesService(ComparecienteRepository repo, CargaDocumentoService carga) {
    this.repo = repo;
    this.carga = carga;
  }

  public record ComparecienteDTO(
      String id,
      String parte,
      String rol,
      String rolEtiqueta,
      int orden,
      String nombres,
      String identificacion,
      String estadoCivil,
      String profesion,
      LocalDate fechaNacimiento,
      Boolean discapacidad,
      boolean personaJuridica,
      boolean conApoderado,
      String origen,
      List<String> documentos) {}

  public record ComparecientesDTO(List<ComparecienteDTO> comparecientes, List<String> avisos) {}

  /** {@code parte}: obligatoria para REPRESENTANTE y APODERADO; en los demás roles se deduce. */
  public record ComparecienteRequest(
      @Size(max = 10) String parte,
      @NotBlank @Size(max = 20) String rol,
      Integer orden,
      @NotBlank @Size(max = 240) String nombres,
      @Size(max = 20) String identificacion,
      @Size(max = 40) String estadoCivil,
      @Size(max = 120) String profesion,
      LocalDate fechaNacimiento,
      Boolean discapacidad,
      boolean personaJuridica,
      boolean conApoderado) {}

  // ---------------------------------------------------------------- lectura

  @Transactional(readOnly = true)
  public ComparecientesDTO listar(String idExpediente) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    List<Compareciente> filas = filas(ctx.tenantId(), ctx.expedienteId());
    return armar(filas, carga.comparecientesPorDocumento(idExpediente));
  }

  /** Para requisitos, minuta y alertas: ordenados por parte y orden. */
  @Transactional(readOnly = true)
  public List<Compareciente> filas(UUID tenantId, UUID expedienteId) {
    if (tenantId == null || expedienteId == null) {
      return List.of();
    }
    return repo.findByTenantIdAndExpedienteIdOrderByParteAscOrdenAsc(tenantId, expedienteId);
  }

  /** Aviso cuando una parte tiene más otorgantes de los que caben en la plantilla. */
  public static List<String> avisosPlantilla(List<Compareciente> filas) {
    List<String> avisos = new ArrayList<>();
    long vendedores = filas.stream().filter(c -> c.parteEnum() == Parte.VENDEDORA && c.rolEnum().otorgante()).count();
    long compradores = filas.stream().filter(c -> c.parteEnum() == Parte.COMPRADORA && c.rolEnum().otorgante()).count();
    if (vendedores > MAX_EN_PLANTILLA) {
      avisos.add("La plantilla admite hasta " + MAX_EN_PLANTILLA + " vendedores; revise la minuta.");
    }
    if (compradores > MAX_EN_PLANTILLA) {
      avisos.add("La plantilla admite hasta " + MAX_EN_PLANTILLA + " compradores; revise la minuta.");
    }
    return avisos;
  }

  // ---------------------------------------------------------------- edición del abogado

  @Transactional
  public ComparecientesDTO crear(String idExpediente, ComparecienteRequest body) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    UUID usuario = usuario();
    Compareciente c = Compareciente.nuevo(ctx.tenantId(), ctx.expedienteId(), Compareciente.ORIGEN_MANUAL, usuario);
    aplicar(c, body, siguienteOrden(ctx, body));
    repo.save(c);
    LOG.info("compareciente creado expediente={} id={}", ctx.expedienteId(), c.getId());
    return listar(idExpediente);
  }

  @Transactional
  public ComparecientesDTO actualizar(String idExpediente, UUID id, ComparecienteRequest body) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    Compareciente c = requerir(ctx, id);
    aplicar(c, body, body.orden() != null ? body.orden() : c.getOrden());
    c.marcarManual(usuario());
    repo.save(c);
    return listar(idExpediente);
  }

  @Transactional
  public ComparecientesDTO eliminar(String idExpediente, UUID id) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    Compareciente c = requerir(ctx, id);
    for (Map.Entry<String, UUID> e : carga.comparecientesPorDocumento(idExpediente).entrySet()) {
      if (id.equals(e.getValue())) {
        carga.ligarCompareciente(idExpediente, e.getKey(), null);
      }
    }
    repo.delete(c);
    // El resto de la lista queda como la dejó el abogado: la IA ya no la reemplaza.
    for (Compareciente otro : filas(ctx.tenantId(), ctx.expedienteId())) {
      if (!otro.getId().equals(id) && Compareciente.ORIGEN_IA.equals(otro.getOrigen())) {
        otro.marcarManual(usuario());
        repo.save(otro);
      }
    }
    return listar(idExpediente);
  }

  /** Liga (o desliga con {@code comparecienteId} null) un documento a una persona del expediente. */
  @Transactional
  public ComparecientesDTO ligarDocumento(String idExpediente, String idDocumento, UUID comparecienteId) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    if (comparecienteId != null) {
      requerir(ctx, comparecienteId);
    }
    carga.ligarCompareciente(idExpediente, idDocumento, comparecienteId);
    return listar(idExpediente);
  }

  // ---------------------------------------------------------------- IA y promoción

  /**
   * Tras el análisis IA: reemplaza los comparecientes de origen IA por las listas del consolidado y
   * liga sus documentos. Si el abogado ya editó alguno, no toca la lista. Transacción propia: un
   * fallo aquí no deja en rollback el análisis que la llama.
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void sincronizarDesdeIa(String idExpediente, DatosExtraidos datos) {
    if (datos == null || (datos.compradores().isEmpty() && datos.vendedores().isEmpty())) {
      return;
    }
    ContextoExpediente ctx = carga.contexto(idExpediente);
    List<Compareciente> actuales = filas(ctx.tenantId(), ctx.expedienteId());
    if (actuales.stream().anyMatch(c -> Compareciente.ORIGEN_MANUAL.equals(c.getOrigen()))) {
      LOG.info("comparecientes: lista editada por el abogado, la IA no la reemplaza expediente={}", ctx.expedienteId());
      return;
    }
    Map<String, UUID> ligados = carga.comparecientesPorDocumento(idExpediente);
    for (Compareciente c : actuales) {
      ligados.entrySet().stream()
          .filter(e -> c.getId().equals(e.getValue()))
          .forEach(e -> carga.ligarCompareciente(idExpediente, e.getKey(), null));
    }
    repo.deleteAll(actuales);
    repo.flush();
    java.util.Set<String> documentos = new java.util.HashSet<>();
    carga.listarDocumentos(idExpediente).forEach(d -> documentos.add(d.idDocumento()));
    int creados = crearDesdeIa(ctx, idExpediente, Parte.COMPRADORA, datos.compradores(), documentos);
    creados += crearDesdeIa(ctx, idExpediente, Parte.VENDEDORA, datos.vendedores(), documentos);
    LOG.info("comparecientes: IA creó={} expediente={}", creados, ctx.expedienteId());
  }

  private int crearDesdeIa(
      ContextoExpediente ctx,
      String idExpediente,
      Parte parte,
      List<Persona> personas,
      java.util.Set<String> documentos) {
    int orden = 0;
    for (Persona p : personas) {
      if (p == null || !StringUtils.hasText(p.nombres())) {
        continue;
      }
      Compareciente c = Compareciente.nuevo(ctx.tenantId(), ctx.expedienteId(), Compareciente.ORIGEN_IA, usuario());
      c.asignar(
          parte,
          rolDesdeIa(parte, p.rol()),
          orden++,
          recortar(p.nombres().trim(), 240),
          recortar(soloIdentificacion(p.cedula()), 20),
          recortar(p.estadoCivil(), 40),
          recortar(p.profesion(), 120),
          fecha(p.fechaNacimiento()),
          p.discapacidad(),
          Boolean.TRUE.equals(p.esPersonaJuridica()),
          Boolean.TRUE.equals(p.compareceConApoderado()));
      repo.save(c);
      for (String docId : p.documentoIds()) {
        if (!StringUtils.hasText(docId) || !documentos.contains(docId.trim())) {
          // La IA citó un id que no es de este expediente: se ignora.
          continue;
        }
        carga.ligarCompareciente(idExpediente, docId.trim(), c.getId());
      }
    }
    return orden;
  }

  static RolCompareciente rolDesdeIa(Parte parte, String rolIa) {
    String r = rolIa == null ? "" : rolIa.trim().toUpperCase(Locale.ROOT);
    boolean compradora = parte == Parte.COMPRADORA;
    return switch (r) {
      case "CONYUGE" -> compradora ? RolCompareciente.CONYUGE_COMPRADOR : RolCompareciente.CONYUGE_VENDEDOR;
      case "HEREDERO" -> compradora ? RolCompareciente.COMPRADOR : RolCompareciente.HEREDERO;
      case "REPRESENTANTE" -> RolCompareciente.REPRESENTANTE;
      case "APODERADO" -> RolCompareciente.APODERADO;
      default -> compradora ? RolCompareciente.COMPRADOR : RolCompareciente.VENDEDOR;
    };
  }

  /** Promoción del borrador: las personas pasan al expediente (los documentos ya van ligados). */
  @Transactional
  public void moverDeBorrador(UUID tenantId, UUID borradorId, UUID caseId) {
    if (tenantId == null || borradorId == null || caseId == null || borradorId.equals(caseId)) {
      return;
    }
    List<Compareciente> filas = repo.findByTenantIdAndExpedienteIdOrderByParteAscOrdenAsc(tenantId, borradorId);
    filas.forEach(c -> c.moverA(caseId));
    repo.saveAll(filas);
  }

  // ---------------------------------------------------------------- apoyo

  private void aplicar(Compareciente c, ComparecienteRequest body, int orden) {
    RolCompareciente rol =
        RolCompareciente.de(body.rol()).orElseThrow(() -> ApiException.badRequest("Rol no válido."));
    Parte parte = rol.parteFija();
    if (parte == null) {
      try {
        parte = Parte.valueOf(body.parte() == null ? "" : body.parte().trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw ApiException.badRequest("Indique si el " + rol.etiqueta().toLowerCase(Locale.ROOT) + " es de la parte compradora o vendedora.");
      }
    }
    c.asignar(
        parte,
        rol,
        Math.max(0, orden),
        body.nombres().trim(),
        vacioANull(soloIdentificacion(body.identificacion())),
        vacioANull(body.estadoCivil()),
        vacioANull(body.profesion()),
        body.fechaNacimiento(),
        body.discapacidad(),
        body.personaJuridica(),
        body.conApoderado());
  }

  private int siguienteOrden(ContextoExpediente ctx, ComparecienteRequest body) {
    if (body.orden() != null) {
      return body.orden();
    }
    return (int) filas(ctx.tenantId(), ctx.expedienteId()).stream()
        .filter(c -> RolCompareciente.de(body.rol()).map(r -> r.parteFija() == null || r.parteFija() == c.parteEnum()).orElse(true))
        .count();
  }

  private Compareciente requerir(ContextoExpediente ctx, UUID id) {
    return repo.findByIdAndTenantIdAndExpedienteId(id, ctx.tenantId(), ctx.expedienteId())
        .orElseThrow(() -> ApiException.notFound("Compareciente no encontrado."));
  }

  private ComparecientesDTO armar(List<Compareciente> filas, Map<String, UUID> ligados) {
    List<ComparecienteDTO> out = new ArrayList<>();
    for (Compareciente c : filas) {
      List<String> docs =
          ligados.entrySet().stream().filter(e -> c.getId().equals(e.getValue())).map(Map.Entry::getKey).toList();
      out.add(
          new ComparecienteDTO(
              c.getId().toString(),
              c.getParte(),
              c.getRol(),
              c.rolEnum().etiqueta(),
              c.getOrden(),
              c.getNombres(),
              c.getIdentificacion(),
              c.getEstadoCivil(),
              c.getProfesion(),
              c.getFechaNacimiento(),
              c.getDiscapacidad(),
              c.isPersonaJuridica(),
              c.isConApoderado(),
              c.getOrigen(),
              docs));
    }
    return new ComparecientesDTO(out, avisosPlantilla(filas));
  }

  static LocalDate fecha(String texto) {
    if (!StringUtils.hasText(texto)) {
      return null;
    }
    String t = texto.trim().replace('-', '/');
    try {
      return LocalDate.parse(t, DMY);
    } catch (DateTimeParseException e) {
      try {
        return LocalDate.parse(texto.trim());
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }

  private static String soloIdentificacion(String valor) {
    if (!StringUtils.hasText(valor)) {
      return null;
    }
    String limpio = valor.replaceAll("[^0-9A-Za-z]", "");
    return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
  }

  private static String vacioANull(String v) {
    return StringUtils.hasText(v) ? v.trim() : null;
  }

  private static String recortar(String v, int max) {
    if (!StringUtils.hasText(v)) {
      return null;
    }
    String t = v.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  private static UUID usuario() {
    AuthPrincipal auth = AuthContext.get();
    return auth == null ? null : auth.userId();
  }
}
