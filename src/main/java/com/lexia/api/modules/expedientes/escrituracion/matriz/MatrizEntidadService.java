package com.lexia.api.modules.expedientes.escrituracion.matriz;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.CaseReadScope;
import com.lexia.api.modules.expedientes.caso.EstatusEtapa;
import com.lexia.api.modules.expedientes.caso.EstatusEtapaRepository;
import com.lexia.api.modules.expedientes.caso.EstatusProceso;
import com.lexia.api.modules.expedientes.caso.EstatusProcesoRepository;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.matriz.MatrizEntidad.Columnas;
import com.lexia.api.modules.expedientes.escrituracion.matriz.MatrizEntidad.Escritura;
import com.lexia.api.modules.expedientes.escrituracion.matriz.MatrizEntidad.FilaMatriz;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.notary.TenantKind;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Llena la matriz que envía la entidad con la etapa y la observación del último estatus de cada
 * expediente, buscando por número de instancia. El archivo se procesa en memoria y no se guarda;
 * logs y auditoría llevan solo totales (ni números de instancia ni textos).
 */
@Service
public class MatrizEntidadService {

  private static final Logger LOG = LoggerFactory.getLogger(MatrizEntidadService.class);

  public enum Clasificacion {
    ACTUALIZADA,
    SIN_ESTATUS,
    NO_ENCONTRADA,
    DUPLICADA,
    OMITIDA_YA_TIENE_TEXTO
  }

  /**
   * @param fila número de fila como en Excel (1-based).
   * @param etapa etiqueta que se escribe (o se escribiría); vacía si no aplica.
   */
  public record FilaResumen(int fila, String numeroInstancia, Clasificacion clasificacion, String etapa) {}

  public record Resumen(Map<Clasificacion, Integer> totales, List<FilaResumen> filas) {}

  public record Llenado(String nombre, byte[] archivo, Map<Clasificacion, Integer> totales) {}

  private final AuthorizationService authorization;
  private final TenantRepository tenants;
  private final LegalCaseRepository legalCases;
  private final EstatusProcesoRepository estatus;
  private final EstatusEtapaRepository etapas;
  private final AuditEventRepository auditEvents;

  public MatrizEntidadService(
      AuthorizationService authorization,
      TenantRepository tenants,
      LegalCaseRepository legalCases,
      EstatusProcesoRepository estatus,
      EstatusEtapaRepository etapas,
      AuditEventRepository auditEvents) {
    this.authorization = authorization;
    this.tenants = tenants;
    this.legalCases = legalCases;
    this.estatus = estatus;
    this.etapas = etapas;
    this.auditEvents = auditEvents;
  }

  @Transactional(readOnly = true)
  public Resumen previsualizar(MultipartFile file, boolean sobrescribir) {
    AuthPrincipal principal = exigirBufete();
    try (XSSFWorkbook book = MatrizEntidad.abrir(bytes(file))) {
      Analisis a = analizar(book, principal, sobrescribir);
      LOG.info("Matriz previsualizada filas={} totales={}", a.filas().size(), a.resumen().totales());
      return a.resumen();
    } catch (IOException ex) {
      throw ApiException.badRequest("No se pudo leer la matriz.");
    }
  }

  @Transactional
  public Llenado llenar(MultipartFile file, boolean sobrescribir) {
    AuthPrincipal principal = exigirBufete();
    try (XSSFWorkbook book = MatrizEntidad.abrir(bytes(file))) {
      Analisis a = analizar(book, principal, sobrescribir);
      byte[] archivo = MatrizEntidad.escribir(book, a.columnas(), a.escrituras());
      Map<Clasificacion, Integer> totales = a.resumen().totales();
      auditEvents.save(
          AuditEvent.of(
              principal.tenantId(),
              principal.userId(),
              "MATRIZ_LLENADA:" + totalesCortos(totales),
              "matriz_entidad",
              null,
              "OK",
              null,
              null));
      LOG.info("Matriz llenada filas={} totales={} bytes={}", a.filas().size(), totales, archivo.length);
      return new Llenado(nombreLlenado(file.getOriginalFilename()), archivo, totales);
    } catch (IOException ex) {
      throw ApiException.badRequest("No se pudo leer la matriz.");
    }
  }

  private record Analisis(
      Columnas columnas, List<FilaMatriz> filas, List<Escritura> escrituras, Resumen resumen) {}

  private Analisis analizar(XSSFWorkbook book, AuthPrincipal principal, boolean sobrescribir) {
    UUID tenantId = principal.tenantId();
    Columnas columnas = MatrizEntidad.ubicar(book);
    List<FilaMatriz> filas = MatrizEntidad.filas(book, columnas);

    Set<String> numeros = filas.stream().map(FilaMatriz::numero).collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, List<LegalCase>> porNumero = new HashMap<>();
    if (!numeros.isEmpty()) {
      for (LegalCase c : legalCases.findEscrituracionPorNumerosInstancia(tenantId, numeros)) {
        porNumero.computeIfAbsent(MatrizEntidad.sinEspacios(c.getNumeroInstancia()), k -> new ArrayList<>()).add(c);
      }
    }
    boolean leeTodo = CaseReadScope.canReadAll(authorization);
    List<UUID> unicos = new ArrayList<>();
    porNumero.values().stream()
        .filter(lista -> lista.size() == 1 && visible(lista.get(0), leeTodo, principal))
        .forEach(lista -> unicos.add(lista.get(0).getId()));
    Map<UUID, EstatusProceso> ultimos = new HashMap<>();
    if (!unicos.isEmpty()) {
      for (EstatusProceso e : estatus.findRecientes(tenantId, unicos)) {
        ultimos.putIfAbsent(e.getCaseId(), e);
      }
    }
    Map<String, String> etiquetas = new HashMap<>();
    if (!ultimos.isEmpty()) {
      for (EstatusEtapa e : etapas.findByTenantId(tenantId)) {
        etiquetas.put(e.getCodigo(), e.etiquetaParaMatriz());
      }
    }

    Map<Clasificacion, Integer> totales = new LinkedHashMap<>();
    for (Clasificacion c : Clasificacion.values()) {
      totales.put(c, 0);
    }
    List<FilaResumen> resumen = new ArrayList<>();
    List<Escritura> escrituras = new ArrayList<>();
    for (FilaMatriz fila : filas) {
      List<LegalCase> casos = porNumero.getOrDefault(fila.numero(), List.of());
      List<LegalCase> visibles = casos.stream().filter(c -> visible(c, leeTodo, principal)).toList();
      Clasificacion clase;
      String etapa = "";
      if (visibles.isEmpty()) {
        // Un expediente que el usuario no puede ver cuenta como inexistente.
        clase = Clasificacion.NO_ENCONTRADA;
      } else if (casos.size() > 1) {
        clase = Clasificacion.DUPLICADA;
      } else {
        EstatusProceso ultimo = ultimos.get(casos.get(0).getId());
        if (ultimo == null) {
          clase = Clasificacion.SIN_ESTATUS;
        } else {
          etapa = etiquetas.getOrDefault(ultimo.getEtapa(), ultimo.getEtapa());
          if (fila.observacionConTexto() && !sobrescribir) {
            clase = Clasificacion.OMITIDA_YA_TIENE_TEXTO;
          } else {
            clase = Clasificacion.ACTUALIZADA;
            escrituras.add(new Escritura(fila.fila(), etapa, ultimo.getTexto()));
          }
        }
      }
      totales.merge(clase, 1, Integer::sum);
      resumen.add(new FilaResumen(fila.fila() + 1, fila.numero(), clase, etapa));
    }
    return new Analisis(columnas, filas, escrituras, new Resumen(totales, resumen));
  }

  private static boolean visible(LegalCase c, boolean leeTodo, AuthPrincipal principal) {
    return leeTodo || Objects.equals(c.getResponsibleMembershipId(), principal.membershipId());
  }

  /** Permiso de lectura de expedientes y tenant bufete (una notaría no tiene esta matriz). */
  private AuthPrincipal exigirBufete() {
    authorization.requirePermission("expedientes:caso:leer");
    AuthPrincipal principal = AuthContext.require();
    String tipo =
        tenants.findById(principal.tenantId()).map(t -> t.getTenantKind()).orElse(null);
    if (!TenantKind.LEGAL_FIRM.name().equals(tipo)) {
      throw new AuthException(HttpStatus.FORBIDDEN, "SOLO_BUFETE", "La matriz de la entidad es solo para bufetes.");
    }
    return principal;
  }

  private static byte[] bytes(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Adjunta la matriz de la entidad (.xlsx).");
    }
    if (file.getSize() > MatrizEntidad.MAX_BYTES) {
      throw ApiException.badRequest("La matriz supera el máximo de 5 MB.");
    }
    try {
      return file.getBytes();
    } catch (IOException ex) {
      throw ApiException.badRequest("No se pudo leer la matriz.");
    }
  }

  /** «Matriz noviembre.xlsx» → «Matriz noviembre_LEXIA.xlsx». Solo el nombre, sin ruta. */
  static String nombreLlenado(String original) {
    String nombre = StringUtils.hasText(original) ? original : "matriz.xlsx";
    nombre = nombre.substring(Math.max(nombre.lastIndexOf('/'), nombre.lastIndexOf('\\')) + 1);
    int punto = nombre.lastIndexOf('.');
    String base = punto > 0 ? nombre.substring(0, punto) : nombre;
    return (base.isBlank() ? "matriz" : base) + "_LEXIA.xlsx";
  }

  /** «A=3,S=1,N=2,D=0,O=1» (cabe en audit_event.event). */
  static String totalesCortos(Map<Clasificacion, Integer> totales) {
    return "A=" + totales.get(Clasificacion.ACTUALIZADA)
        + ",S=" + totales.get(Clasificacion.SIN_ESTATUS)
        + ",N=" + totales.get(Clasificacion.NO_ENCONTRADA)
        + ",D=" + totales.get(Clasificacion.DUPLICADA)
        + ",O=" + totales.get(Clasificacion.OMITIDA_YA_TIENE_TEXTO);
  }

  /** Cabecera de la descarga: {@code ACTUALIZADA=3;SIN_ESTATUS=1;…}. */
  public static String cabeceraTotales(Map<Clasificacion, Integer> totales) {
    return totales.entrySet().stream()
        .map(e -> e.getKey().name() + "=" + e.getValue())
        .collect(Collectors.joining(";"));
  }
}
