package com.lexia.api.modules.ia.llm;

import com.lexia.api.modules.ia.ProveedorIaErrores;
import com.lexia.api.modules.ia.claude.ClaudeAnalysisService;
import com.lexia.api.modules.ia.gemini.GeminiAnalysisService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ClasificacionDocumento;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCapturaBiess;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionMinutaVivienda;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Delega al proveedor primario ({@code llm.provider}) y conmuta al alternativo en fallos del proveedor. */
@Primary
@Service
public class FailoverAnalisisDocumentoService implements AnalisisDocumentoService {

  private static final Logger LOG = LoggerFactory.getLogger(FailoverAnalisisDocumentoService.class);

  private final ObjectProvider<ClaudeAnalysisService> claude;
  private final ObjectProvider<GeminiAnalysisService> gemini;
  private final String primaryProvider;
  private final boolean failoverEnabled;

  public FailoverAnalisisDocumentoService(
      ObjectProvider<ClaudeAnalysisService> claude,
      ObjectProvider<GeminiAnalysisService> gemini,
      @Value("${llm.provider:claude}") String primaryProvider,
      @Value("${llm.failover.enabled:true}") boolean failoverEnabled) {
    this.claude = claude;
    this.gemini = gemini;
    this.primaryProvider = primaryProvider == null ? "claude" : primaryProvider.trim();
    this.failoverEnabled = failoverEnabled;
  }

  @Override
  public boolean isConfigured() {
    return proveedoresOrdenados().stream().anyMatch(AnalisisDocumentoService::isConfigured);
  }

  @Override
  public ClasificacionDocumento clasificarDocumento(
      String textoOcr, List<String> tipos, Duration limite) {
    RuntimeException ultima = null;
    for (AnalisisDocumentoService proveedor : proveedoresOrdenados()) {
      if (!proveedor.isConfigured()) {
        continue;
      }
      try {
        return proveedor.clasificarDocumento(textoOcr, tipos, limite);
      } catch (RuntimeException ex) {
        ultima = ex;
        if (!intentaSiguiente(ex.getMessage())) {
          throw ex;
        }
        LOG.warn("failover clasificacion {} -> siguiente: {}", nombre(proveedor), ex.getMessage());
      }
    }
    if (ultima != null) {
      throw ultima;
    }
    throw new UnsupportedOperationException("Clasificacion: ningun proveedor LLM configurado.");
  }

  @Override
  public ExtraccionExpedienteCompleto procesarExpedienteCompleto(String ocrMarcado, String systemPrompt) {
    return conFailover(
        p -> p.procesarExpedienteCompleto(ocrMarcado, systemPrompt),
        r -> r.motivo(),
        r -> "ERROR".equals(r.estado()),
        ExtraccionExpedienteCompleto.error("Ningun proveedor LLM disponible para cotejo."));
  }

  @Override
  public ExtraccionMinutaVivienda extraerMinutaVivienda(
      String ocrConsolidado, String productCode, String templateKind) {
    return conFailover(
        p -> p.extraerMinutaVivienda(ocrConsolidado, productCode, templateKind),
        ExtraccionMinutaVivienda::motivo,
        r -> "ERROR".equals(r.estado()),
        ExtraccionMinutaVivienda.error("Ningun proveedor LLM disponible para minuta."));
  }

  @Override
  public ExtraccionCapturaBiess extraerCapturaBiess(String textoCaptura) {
    return conFailover(
        p -> p.extraerCapturaBiess(textoCaptura),
        ExtraccionCapturaBiess::motivo,
        r -> "ERROR".equals(r.estado()),
        ExtraccionCapturaBiess.error("Ningun proveedor LLM disponible para captura BIESS."));
  }

  @Override
  public ValidacionDocumentoJson validarDocumento(String systemPrompt, String textoDocumento) {
    return conFailover(
        p -> p.validarDocumento(systemPrompt, textoDocumento),
        ValidacionDocumentoJson::error,
        ValidacionDocumentoJson::esError,
        ValidacionDocumentoJson.error("Ningun proveedor LLM disponible para validacion."));
  }

  @Override
  public ValidacionDocumentoJson diagnosticarCoactiva(String systemPrompt, String textoExpediente) {
    return conFailover(
        p -> p.diagnosticarCoactiva(systemPrompt, textoExpediente),
        ValidacionDocumentoJson::error,
        ValidacionDocumentoJson::esError,
        ValidacionDocumentoJson.error("Ningun proveedor LLM disponible para diagnostico coactivas."));
  }

  private <T> T conFailover(
      Function<AnalisisDocumentoService, T> invocar,
      Function<T, String> motivoSiError,
      Predicate<T> esError,
      T sinProveedores) {
    T ultimo = null;
    boolean huboFalloPrimario = false;
    for (AnalisisDocumentoService proveedor : proveedoresOrdenados()) {
      if (!proveedor.isConfigured()) {
        continue;
      }
      try {
        T resultado = invocar.apply(proveedor);
        if (!esError.test(resultado)) {
          if (huboFalloPrimario) {
            registrarFailoverOk(nombre(proveedor));
            LOG.info("failover LLM exitoso con proveedor {}", nombre(proveedor));
          }
          return resultado;
        }
        ultimo = resultado;
        huboFalloPrimario = true;
        String motivo = motivoSiError.apply(resultado);
        if (!intentaSiguiente(motivo)) {
          return resultado;
        }
        LOG.warn("failover LLM {} fallo ({}) -> siguiente proveedor", nombre(proveedor), motivo);
      } catch (RuntimeException ex) {
        huboFalloPrimario = true;
        if (!intentaSiguiente(ex.getMessage())) {
          throw ex;
        }
        LOG.warn("failover LLM {} excepcion -> siguiente: {}", nombre(proveedor), ex.getMessage());
      }
    }
    return ultimo != null ? ultimo : sinProveedores;
  }

  private boolean intentaSiguiente(String motivo) {
    return failoverEnabled && ProveedorIaErrores.admiteFailoverProveedor(motivo);
  }

  private List<AnalisisDocumentoService> proveedoresOrdenados() {
    AnalisisDocumentoService claudeSvc = claude.getIfAvailable();
    AnalisisDocumentoService geminiSvc = gemini.getIfAvailable();
    boolean claudePrimero = primaryProvider.equalsIgnoreCase("claude");
    List<AnalisisDocumentoService> orden = new ArrayList<>(2);
    if (claudePrimero) {
      if (claudeSvc != null) orden.add(claudeSvc);
      if (geminiSvc != null) orden.add(geminiSvc);
    } else {
      if (geminiSvc != null) orden.add(geminiSvc);
      if (claudeSvc != null) orden.add(claudeSvc);
    }
    return orden;
  }

  private static String nombre(AnalisisDocumentoService proveedor) {
    if (proveedor instanceof ClaudeAnalysisService) {
      return "claude";
    }
    if (proveedor instanceof GeminiAnalysisService) {
      return "gemini";
    }
    return proveedor.getClass().getSimpleName();
  }

  private static void registrarFailoverOk(String proveedor) {
    Counter.builder("lexia.ia.failover.ok")
        .tag("proveedor", proveedor)
        .register(Metrics.globalRegistry)
        .increment();
  }
}