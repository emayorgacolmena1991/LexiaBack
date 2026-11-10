package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.TipoPermitidoDTO;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore.CaseFileContext;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore.ClasificacionActual;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ClasificacionDocumento;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Clasificación automática del tipo documental con el texto OCR, justo después de extraerlo. En un
 * expediente el estado vive en {@code app.document}; en un borrador, en su documento en memoria, y
 * pasa al {@code LegalDocument} en la promoción. Nunca rompe el OCR: si el LLM falla, el documento
 * queda en ERROR y el usuario lo clasifica a mano.
 */
@Service
public class ClasificacionDocumentoService {

  private static final Logger LOG = LoggerFactory.getLogger(ClasificacionDocumentoService.class);
  public static final String OTRO = "OTRO";
  static final int MAX_CARACTERES = 4000;

  private final CaseDocumentoStore caseDocs;
  private final CargaDocumentoService carga;
  private final ObjectProvider<AnalisisDocumentoService> llm;
  private final BigDecimal umbral;
  private final Duration timeout;

  public ClasificacionDocumentoService(
      CaseDocumentoStore caseDocs,
      CargaDocumentoService carga,
      ObjectProvider<AnalisisDocumentoService> llm,
      @Value("${lexia.clasificacion.umbral:0.8}") double umbral,
      @Value("${lexia.clasificacion.timeout-ms:8000}") long timeoutMs) {
    this.caseDocs = caseDocs;
    this.carga = carga;
    this.llm = llm;
    this.umbral = BigDecimal.valueOf(umbral);
    this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
  }

  /** ASIGNAR: upload y OCR normal. SOLO_DETECTAR: reemplazo; el documento conserva su tipo. */
  public enum Modo {
    ASIGNAR,
    SOLO_DETECTAR
  }

  /**
   * @param tipoAsignado tipo que quedó en el documento por esta clasificación (null si no asignó).
   * @param tipoDetectado lo que propuso el LLM (normalizado a la lista u {@code OTRO}).
   * @param tipoVigente tipo del documento después de clasificar.
   */
  public record Resultado(
      String estado, String tipoAsignado, String tipoDetectado, BigDecimal confianza, String tipoVigente) {

    public static final Resultado OMITIDO = new Resultado(null, null, null, null, null);

    public boolean ejecutado() {
      return estado != null;
    }
  }

  /** Dónde vive el estado: {@code app.document} (expediente) o el documento del borrador en RAM. */
  private interface Destino {
    ClasificacionActual actual();

    String aplicar(String tipo, BigDecimal confianza, String modelo, String estado, boolean asignar);

    void error();
  }

  /** {@code null} si no hay nada que clasificar: escaneado (tipo fijo) o documento inexistente. */
  private Destino destino(String idExpediente, String idDocumento) {
    CaseFileContext ctx = caseDocs.context(idExpediente);
    if (ctx != null) {
      if (ctx.ingestionMode().isFisicoEscaneado()) {
        return null;
      }
      return new Destino() {
        @Override
        public ClasificacionActual actual() {
          return caseDocs.clasificacionActual(ctx, idDocumento);
        }

        @Override
        public String aplicar(
            String tipo, BigDecimal confianza, String modelo, String estado, boolean asignar) {
          return caseDocs.aplicarClasificacion(
              ctx, idDocumento, tipo, confianza, modelo, estado, asignar);
        }

        @Override
        public void error() {
          caseDocs.registrarErrorClasificacion(ctx, idDocumento, null);
        }
      };
    }
    ClasificacionActual borrador =
        carga.clasificacionActualBorrador(idExpediente, idDocumento).orElse(null);
    if (borrador == null) {
      return null;
    }
    return new Destino() {
      @Override
      public ClasificacionActual actual() {
        return borrador;
      }

      @Override
      public String aplicar(
          String tipo, BigDecimal confianza, String modelo, String estado, boolean asignar) {
        return carga.aplicarClasificacionBorrador(
            idExpediente, idDocumento, tipo, confianza, modelo, estado, asignar);
      }

      @Override
      public void error() {
        carga.registrarErrorClasificacionBorrador(idExpediente, idDocumento, null);
      }
    };
  }

  public Resultado clasificar(String idExpediente, String idDocumento, String textoOcr, Modo modo) {
    if (!StringUtils.hasText(textoOcr)) {
      return Resultado.OMITIDO;
    }
    Destino destino = destino(idExpediente, idDocumento);
    if (destino == null) {
      return Resultado.OMITIDO;
    }
    ClasificacionActual actual = destino.actual();
    if (modo == Modo.ASIGNAR && actual.fijadoPorUsuario()) {
      return Resultado.OMITIDO;
    }
    List<String> tipos = tiposDelCatalogo(idExpediente);
    if (tipos.isEmpty()) {
      LOG.info("Clasificación omitida doc={}: el expediente no tiene catálogo de tipos", idDocumento);
      return Resultado.OMITIDO;
    }
    AnalisisDocumentoService proveedor = llm.getIfAvailable();
    ClasificacionDocumento respuesta;
    try {
      if (proveedor == null) {
        throw new IllegalStateException("Sin proveedor LLM.");
      }
      respuesta = proveedor.clasificarDocumento(recortar(textoOcr), tipos, timeout);
    } catch (RuntimeException e) {
      // Sin texto ni respuesta del documento en el log.
      LOG.warn(
          "Clasificación falló doc={}: {}; queda en ERROR para clasificación manual",
          idDocumento,
          e.getClass().getSimpleName());
      destino.error();
      return new Resultado(LegalDocument.ESTADO_ERROR, null, null, null, actual.docType());
    }
    String tipo = normalizar(respuesta.tipo(), tipos);
    BigDecimal confianza = confianza(respuesta.confianza());
    boolean supera = confianza.compareTo(umbral) >= 0 && !OTRO.equals(tipo);
    String estado;
    boolean asignar;
    if (modo == Modo.SOLO_DETECTAR) {
      asignar = false;
      estado =
          tipo.equals(actual.docType())
              ? LegalDocument.ESTADO_CLASIFICADO
              : LegalDocument.ESTADO_POR_REVISAR;
    } else {
      asignar = supera;
      estado = supera ? LegalDocument.ESTADO_CLASIFICADO : LegalDocument.ESTADO_POR_REVISAR;
    }
    String vigente = destino.aplicar(tipo, confianza, respuesta.modelo(), estado, asignar);
    boolean asignado = asignar && tipo.equals(vigente);
    LOG.info(
        "Clasificación doc={} modo={} tipo={} confianza={} estado={} asignado={}",
        idDocumento,
        modo,
        tipo,
        confianza,
        estado,
        asignado);
    return new Resultado(estado, asignado ? tipo : null, tipo, confianza, vigente);
  }

  private List<String> tiposDelCatalogo(String idExpediente) {
    Set<String> codigos = new LinkedHashSet<>();
    for (TipoPermitidoDTO tipo : carga.tiposPermitidos(idExpediente)) {
      if (tipo != null && StringUtils.hasText(tipo.codigo()) && !OTRO.equals(tipo.codigo().trim())) {
        codigos.add(tipo.codigo().trim());
      }
    }
    return List.copyOf(codigos);
  }

  static String normalizar(String tipo, List<String> tipos) {
    String t = tipo == null ? "" : tipo.trim();
    return tipos.contains(t) ? t : OTRO;
  }

  static BigDecimal confianza(double valor) {
    double acotada = Double.isNaN(valor) ? 0 : Math.max(0, Math.min(1, valor));
    return BigDecimal.valueOf(acotada).setScale(3, RoundingMode.HALF_UP);
  }

  static String recortar(String texto) {
    return texto.length() <= MAX_CARACTERES ? texto : texto.substring(0, MAX_CARACTERES);
  }
}
