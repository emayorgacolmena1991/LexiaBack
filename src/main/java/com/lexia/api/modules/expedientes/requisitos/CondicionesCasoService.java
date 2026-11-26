package com.lexia.api.modules.expedientes.requisitos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.ContextoExpediente;
import com.lexia.api.modules.expedientes.escrituracion.core.IaAnalysisService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.CondicionDetectada;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.DeteccionCondiciones;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.OpcionCondicion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Condiciones del caso de un borrador o expediente: la IA las propone desde el OCR (con evidencia),
 * el abogado las confirma, descarta o agrega a mano. Solo las confirmadas cambian los requisitos
 * ({@link RequisitosService}). CREDITO_PREFERENCIAL la fija el producto preferencial;
 * FINANCIAMIENTO_GASTOS es solo manual.
 *
 * <p>La detección llama al LLM en el hilo del request, igual que {@code analizar-ia} del wizard:
 * una sola llamada con texto acotado ({@link #MAX_CARACTERES}) y timeout propio. No va por
 * {@code app.jobs} porque el wizard espera el resultado antes del cotejo.
 */
@Service
public class CondicionesCasoService {

  private static final Logger LOG = LoggerFactory.getLogger(CondicionesCasoService.class);
  static final int MAX_CARACTERES = 120_000;

  public static final String ESTADO_PROPUESTA = "PROPUESTA";
  public static final String ESTADO_CONFIRMADA = "CONFIRMADA";
  public static final String ESTADO_DESCARTADA = "DESCARTADA";
  public static final String ORIGEN_PRODUCTO = "PRODUCTO";

  public static final String ACCION_CONFIRMAR = "CONFIRMAR";
  public static final String ACCION_DESCARTAR = "DESCARTAR";

  private final ExpedienteCondicionRepository repo;
  private final CargaDocumentoService carga;
  private final ObjectProvider<IaAnalysisService> ia;
  private final ObjectProvider<AnalisisDocumentoService> llm;
  private final Duration timeout;

  public CondicionesCasoService(
      ExpedienteCondicionRepository repo,
      CargaDocumentoService carga,
      ObjectProvider<IaAnalysisService> ia,
      ObjectProvider<AnalisisDocumentoService> llm,
      @Value("${lexia.condiciones.timeout-ms:60000}") long timeoutMs) {
    this.repo = repo;
    this.carga = carga;
    this.ia = ia;
    this.llm = llm;
    this.timeout = Duration.ofMillis(Math.max(5000, timeoutMs));
  }

  /**
   * @param editable false si la fija el producto (preferencial).
   * @param documentoId documento que la evidencia (null si no es un documento del expediente).
   */
  public record CondicionDTO(
      String codigo,
      String descripcion,
      String estado,
      String origen,
      String evidencia,
      String documentoId,
      BigDecimal confianza,
      boolean editable,
      Instant actualizadoEn) {}

  public record OpcionCondicionDTO(String codigo, String descripcion, boolean soloManual) {}

  /** {@code condiciones}: las que tienen estado; {@code catalogo}: todas, para agregar a mano. */
  public record CondicionesCasoDTO(
      List<CondicionDTO> condiciones, List<OpcionCondicionDTO> catalogo) {}

  /** {@code accion}: CONFIRMAR (también agrega una manual) o DESCARTAR. */
  public record DecisionCondicion(String codigo, String accion) {}

  @Transactional(readOnly = true)
  public CondicionesCasoDTO listar(String idExpediente) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    return armar(ctx, repo.findByTenantIdAndExpedienteId(ctx.tenantId(), ctx.expedienteId()));
  }

  @Transactional
  public CondicionesCasoDTO actualizar(String idExpediente, List<DecisionCondicion> decisiones) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    UUID usuario = usuario();
    Map<String, ExpedienteCondicion> actuales = porCodigo(ctx);
    for (DecisionCondicion d : decisiones == null ? List.<DecisionCondicion>of() : decisiones) {
      CondicionCaso condicion =
          CondicionCaso.de(d == null ? null : d.codigo())
              .orElseThrow(() -> ApiException.badRequest("Condición desconocida."));
      if (condicion == CondicionCaso.CREDITO_PREFERENCIAL && esPreferencial(ctx)) {
        throw ApiException.badRequest("El crédito preferencial lo define el producto.");
      }
      String accion = d.accion() == null ? "" : d.accion().trim().toUpperCase(java.util.Locale.ROOT);
      ExpedienteCondicion fila = actuales.get(condicion.name());
      switch (accion) {
        case ACCION_CONFIRMAR -> {
          if (fila == null) {
            fila =
                ExpedienteCondicion.nueva(
                    ctx.tenantId(),
                    ctx.expedienteId(),
                    condicion,
                    ExpedienteCondicion.ORIGEN_MANUAL,
                    usuario);
            actuales.put(condicion.name(), fila);
          }
          fila.confirmar(usuario);
          repo.save(fila);
        }
        case ACCION_DESCARTAR -> {
          if (fila != null) {
            fila.descartar(usuario);
            repo.save(fila);
          }
        }
        default -> throw ApiException.badRequest("Acción no válida: use CONFIRMAR o DESCARTAR.");
      }
    }
    LOG.info(
        "condiciones expediente={} decisiones={} tenant={}",
        ctx.expedienteId(),
        decisiones == null ? 0 : decisiones.size(),
        ctx.tenantId());
    return armar(ctx, new ArrayList<>(actuales.values()));
  }

  /**
   * La IA propone condiciones a partir del OCR del expediente. Las decisiones del abogado
   * (confirmada o descartada) se respetan; solo se actualiza la evidencia. Sin transacción
   * envolvente: no se retiene una conexión del pool durante la llamada al LLM (regla 6.4); cada
   * fila se guarda por separado.
   */
  public CondicionesCasoDTO detectar(String idExpediente) {
    ContextoExpediente ctx = carga.contexto(idExpediente);
    IaAnalysisService analisis = ia.getIfAvailable();
    AnalisisDocumentoService proveedor = llm.getIfAvailable();
    if (analisis == null || proveedor == null || !proveedor.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para detectar condiciones.");
    }
    String texto = analisis.textoOcrParaIa(ctx.expedienteId(), ctx.persistido());
    if (texto.length() > MAX_CARACTERES) {
      texto = texto.substring(0, MAX_CARACTERES);
    }
    List<OpcionCondicion> opciones =
        Arrays.stream(CondicionCaso.values())
            .filter(CondicionCaso::detectablePorIa)
            .map(c -> new OpcionCondicion(c.name(), c.pista()))
            .toList();
    DeteccionCondiciones deteccion;
    try {
      deteccion = proveedor.detectarCondiciones(texto, opciones, timeout);
    } catch (RuntimeException e) {
      // Sin texto ni respuesta del LLM en el log.
      LOG.warn(
          "condiciones: detección falló expediente={} causa={}",
          ctx.expedienteId(),
          e.getClass().getSimpleName());
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "LLM_FAILED",
          "No se pudieron detectar las condiciones. Puede agregarlas a mano.");
    }
    Map<String, CondicionDetectada> mejores = new LinkedHashMap<>();
    for (CondicionDetectada d : deteccion.condiciones()) {
      CondicionCaso.de(d.codigo())
          .filter(CondicionCaso::detectablePorIa)
          .ifPresent(
              c ->
                  mejores.merge(
                      c.name(), d, (a, b) -> b.confianza() > a.confianza() ? b : a));
    }
    Map<String, ExpedienteCondicion> actuales = porCodigo(ctx);
    UUID usuario = usuario();
    for (Map.Entry<String, CondicionDetectada> e : mejores.entrySet()) {
      CondicionDetectada d = e.getValue();
      ExpedienteCondicion fila = actuales.get(e.getKey());
      if (fila == null) {
        fila =
            ExpedienteCondicion.nueva(
                ctx.tenantId(),
                ctx.expedienteId(),
                CondicionCaso.valueOf(e.getKey()),
                ExpedienteCondicion.ORIGEN_IA,
                usuario);
        actuales.put(e.getKey(), fila);
      }
      fila.registrarPropuesta(recortar(d.evidencia(), 500), uuidONull(d.documentoId()), confianza(d.confianza()));
      repo.save(fila);
    }
    LOG.info(
        "condiciones: IA propuso={} expediente={} modelo={}",
        mejores.size(),
        ctx.expedienteId(),
        deteccion.modelo());
    return armar(ctx, new ArrayList<>(actuales.values()));
  }

  /** Promoción del borrador: sus condiciones pasan al expediente oficial. */
  @Transactional
  public void moverDeBorrador(UUID tenantId, UUID borradorId, UUID caseId) {
    if (tenantId == null || borradorId == null || caseId == null || borradorId.equals(caseId)) {
      return;
    }
    List<ExpedienteCondicion> filas = repo.findByTenantIdAndExpedienteId(tenantId, borradorId);
    for (ExpedienteCondicion fila : filas) {
      fila.moverA(caseId);
    }
    repo.saveAll(filas);
  }

  private CondicionesCasoDTO armar(ContextoExpediente ctx, List<ExpedienteCondicion> filas) {
    List<CondicionDTO> out = new ArrayList<>();
    boolean preferencial = esPreferencial(ctx);
    for (CondicionCaso c : CondicionCaso.values()) {
      if (c == CondicionCaso.CREDITO_PREFERENCIAL && preferencial) {
        out.add(
            new CondicionDTO(
                c.name(), c.descripcion(), ESTADO_CONFIRMADA, ORIGEN_PRODUCTO, null, null, null, false, null));
        continue;
      }
      filas.stream()
          .filter(f -> c.name().equals(f.getCondicion()))
          .findFirst()
          .ifPresent(
              f ->
                  out.add(
                      new CondicionDTO(
                          c.name(),
                          c.descripcion(),
                          f.isDescartada()
                              ? ESTADO_DESCARTADA
                              : f.isConfirmada() ? ESTADO_CONFIRMADA : ESTADO_PROPUESTA,
                          f.getOrigen(),
                          f.getEvidencia(),
                          f.getDocumentoId() == null ? null : f.getDocumentoId().toString(),
                          f.getConfianza(),
                          true,
                          f.getUpdatedAt())));
    }
    List<OpcionCondicionDTO> catalogo =
        Arrays.stream(CondicionCaso.values())
            .filter(c -> !(c == CondicionCaso.CREDITO_PREFERENCIAL && preferencial))
            .map(c -> new OpcionCondicionDTO(c.name(), c.descripcion(), !c.detectablePorIa()))
            .toList();
    return new CondicionesCasoDTO(out, catalogo);
  }

  private Map<String, ExpedienteCondicion> porCodigo(ContextoExpediente ctx) {
    Map<String, ExpedienteCondicion> out = new LinkedHashMap<>();
    for (ExpedienteCondicion f : repo.findByTenantIdAndExpedienteId(ctx.tenantId(), ctx.expedienteId())) {
      out.put(f.getCondicion(), f);
    }
    return out;
  }

  private static boolean esPreferencial(ContextoExpediente ctx) {
    return ctx.productCode() != null
        && RequisitosService.PRODUCTO_PREFERENCIAL.equalsIgnoreCase(ctx.productCode().trim());
  }

  private static UUID usuario() {
    AuthPrincipal auth = AuthContext.get();
    return auth == null ? null : auth.userId();
  }

  private static UUID uuidONull(String valor) {
    try {
      return StringUtils.hasText(valor) ? UUID.fromString(valor.trim()) : null;
    } catch (IllegalArgumentException e) {
      // Documento del borrador (DOC-xxxx): no es un id persistente.
      return null;
    }
  }

  private static BigDecimal confianza(double valor) {
    double acotada = Double.isNaN(valor) ? 0 : Math.max(0, Math.min(1, valor));
    return BigDecimal.valueOf(acotada).setScale(3, RoundingMode.HALF_UP);
  }

  private static String recortar(String texto, int max) {
    if (texto == null) {
      return null;
    }
    String t = texto.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }
}
