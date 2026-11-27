package com.lexia.api.modules.expedientes.requisitos;

import com.lexia.api.modules.expedientes.comparecientes.Compareciente;
import com.lexia.api.modules.expedientes.comparecientes.ComparecienteRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore.DocumentoExtraidoDTO;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Calcula VIGENTE / POR_VENCER / VENCIDO / SIN_FECHA por documento usando las fechas extraídas
 * por la IA y reglas fijas por tipo. No hace llamadas LLM.
 */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class VigenciaDocumentoService {

  private static final Logger LOG = LoggerFactory.getLogger(VigenciaDocumentoService.class);

  public enum EstadoVigencia {
    VIGENTE,
    POR_VENCER,
    VENCIDO,
    SIN_FECHA,
    /** dd/MM o MM/dd sin forma de saberlo: aviso para que el abogado confirme. */
    FECHA_AMBIGUA
  }

  /** Fecha leída del OCR; {@code ambigua}: día y mes podrían estar invertidos. */
  record Fecha(LocalDate valor, boolean ambigua) {}

  public record ResultadoVigencia(
      UUID documentId,
      String docType,
      String docNombre,
      EstadoVigencia estado,
      String mensaje,
      String fechaEfectiva) {}

  private static final int DIAS_POR_VENCER = 7;
  private static final int DIAS_CERTIFICADO_PRESENTACION = 90;
  private static final int DIAS_PODER = 365;
  private static final ZoneId ZONA = ZoneId.of("America/Guayaquil");
  private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;

  private static final Pattern FECHA_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern FECHA_NUM =
      Pattern.compile("(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{4})");
  private static final Pattern FECHA_TEXTO =
      Pattern.compile("(?i)(\\d{1,2})\\s*(?:de\\s+)?([a-záéíóúñ]{3,})\\s*(?:de\\s+)?(\\d{4})");

  private final DocumentosExtraidosStore extractados;
  private final TenantParameterService tenantParameters;
  private final ComparecienteRepository comparecienteRepo;

  public VigenciaDocumentoService(
      DocumentosExtraidosStore extractados,
      TenantParameterService tenantParameters,
      ComparecienteRepository comparecienteRepo) {
    this.extractados = extractados;
    this.tenantParameters = tenantParameters;
    this.comparecienteRepo = comparecienteRepo;
  }

  public List<ResultadoVigencia> calcular(
      UUID tenantId,
      UUID caseId,
      List<LegalDocument> docs,
      List<RequisitosService.RequisitoAplicable> requisitos) {
    LocalDate hoy = LocalDate.now(ZONA);
    List<DocumentoExtraidoDTO> extraidos = extractados.cargar(caseId, tenantId);
    Map<String, DocumentoExtraidoDTO> porTipo = indexarPorTipo(extraidos);
    List<Compareciente> comparecientes =
        comparecienteRepo.findByTenantIdAndExpedienteIdOrderByParteAscOrdenAsc(tenantId, caseId);

    List<ResultadoVigencia> out = new ArrayList<>();
    for (LegalDocument doc : docs) {
      String tipo = doc.getDocType();
      if (!StringUtils.hasText(tipo) || LegalDocument.TIPO_OTRO.equals(tipo.trim())) {
        continue;
      }
      String t = tipo.trim().toUpperCase(Locale.ROOT);
      DocumentoExtraidoDTO extraido = porTipo.get(t);
      UUID cid = doc.getComparecienteId();
      Compareciente comp =
          cid != null
              ? comparecientes.stream().filter(c -> c.getId().equals(cid)).findFirst().orElse(null)
              : null;
      int maxDias =
          requisitos.stream()
              .filter(r -> t.equals(r.tipo()))
              .mapToInt(RequisitosService.RequisitoAplicable::maxValidityDays)
              .findFirst()
              .orElse(0);
      out.add(calcularDocumento(doc, t, extraido, comp, maxDias, hoy, tenantId));
    }
    return List.copyOf(out);
  }

  private ResultadoVigencia calcularDocumento(
      LegalDocument doc,
      String tipo,
      DocumentoExtraidoDTO extraido,
      Compareciente comp,
      int maxDias,
      LocalDate hoy,
      UUID tenantId) {

    // PAPELETA: exento si el compareciente tiene ≥ 65 años
    if ("PAPELETA".equals(tipo) && esExentoPorEdad(comp, hoy)) {
      return ok(doc, tipo, "Exento: compareciente mayor de 65 años.", null);
    }

    // CEDULA: vigencia por fecha de vencimiento del documento mismo
    if ("CEDULA".equals(tipo)) {
      return porFecha(doc, tipo, fechaCampo(extraido, "fechaVencimiento"), 0, hoy);
    }

    // PAPELETA: válida hasta el día antes del próximo proceso electoral
    if ("PAPELETA".equals(tipo)) {
      return vigenciaPapeleta(doc, tenantId, hoy);
    }

    // CERTIFICADO_PRESENTACION: 90 días desde emisión
    if ("CERTIFICADO_PRESENTACION".equals(tipo)) {
      return desdeEmision(doc, tipo, extraido, hoy, DIAS_CERTIFICADO_PRESENTACION);
    }

    // PODER: 1 año desde emisión
    if ("PODER".equals(tipo)) {
      return desdeEmision(doc, tipo, extraido, hoy, DIAS_PODER);
    }

    // Otros: si el doc tiene fechaVencimiento explícita, usarla
    Fecha venc = fechaCampo(extraido, "fechaVencimiento");
    if (venc != null) {
      return porFecha(doc, tipo, venc, 0, hoy);
    }
    // O calcular desde emisión + maxValidityDays
    if (maxDias > 0) {
      Fecha emision = fechaCampo(extraido, "fechaEmision");
      if (emision != null) {
        return porFecha(doc, tipo, emision, maxDias, hoy);
      }
    }
    return sinFecha(doc, tipo);
  }

  /** Sin fecha, fecha ambigua o vencimiento = fecha + {@code dias}. */
  private ResultadoVigencia porFecha(LegalDocument doc, String tipo, Fecha fecha, int dias, LocalDate hoy) {
    if (fecha == null) {
      return sinFecha(doc, tipo);
    }
    if (fecha.ambigua()) {
      return new ResultadoVigencia(
          doc.getId(), tipo, doc.getName(), EstadoVigencia.FECHA_AMBIGUA,
          "Fecha ambigua (día/mes): confirme la fecha del documento.", null);
    }
    return evaluar(doc, tipo, fecha.valor().plusDays(dias), hoy);
  }

  private ResultadoVigencia vigenciaPapeleta(LegalDocument doc, UUID tenantId, LocalDate hoy) {
    String ultimoRaw =
        tenantParameters.getValue(
            tenantId,
            TenantParameterService.KEY_FECHA_ULTIMO_PROCESO_ELECTORAL,
            "2023-02-05");
    // Pendiente de confirmar con la abogada: plazo entre procesos (por defecto 4 años).
    String aniosRaw =
        tenantParameters.getValue(tenantId, TenantParameterService.KEY_PAPELETA_VIGENCIA_ANIOS, "4");
    try {
      LocalDate ultimoProceso = LocalDate.parse(ultimoRaw.trim());
      // La papeleta vence el día anterior al próximo proceso electoral.
      LocalDate venceEl = ultimoProceso.plusYears(Integer.parseInt(aniosRaw.trim())).minusDays(1);
      return evaluar(doc, "PAPELETA", venceEl, hoy);
    } catch (Exception e) {
      LOG.warn("vigenciaPapeleta: no se pudo calcular (param={}): {}", ultimoRaw, e.getMessage());
      return sinFecha(doc, "PAPELETA");
    }
  }

  private ResultadoVigencia desdeEmision(
      LegalDocument doc, String tipo, DocumentoExtraidoDTO extraido, LocalDate hoy, int dias) {
    return porFecha(doc, tipo, fechaCampo(extraido, "fechaEmision"), dias, hoy);
  }

  private ResultadoVigencia evaluar(LegalDocument doc, String tipo, LocalDate vence, LocalDate hoy) {
    if (!hoy.isAfter(vence.minusDays(DIAS_POR_VENCER + 1))) {
      return ok(doc, tipo, "Vigente hasta " + vence.format(FMT) + ".", vence.format(FMT));
    }
    if (!hoy.isAfter(vence)) {
      return new ResultadoVigencia(
          doc.getId(), tipo, doc.getName(),
          EstadoVigencia.POR_VENCER,
          "Vence pronto: " + vence.format(FMT) + ".",
          vence.format(FMT));
    }
    return new ResultadoVigencia(
        doc.getId(), tipo, doc.getName(),
        EstadoVigencia.VENCIDO,
        "Venció el " + vence.format(FMT) + ".",
        vence.format(FMT));
  }

  private ResultadoVigencia ok(LegalDocument doc, String tipo, String msg, String fecha) {
    return new ResultadoVigencia(doc.getId(), tipo, doc.getName(), EstadoVigencia.VIGENTE, msg, fecha);
  }

  private ResultadoVigencia sinFecha(LegalDocument doc, String tipo) {
    return new ResultadoVigencia(
        doc.getId(), tipo, doc.getName(), EstadoVigencia.SIN_FECHA,
        "Vigencia no indicada en el documento.", null);
  }

  private boolean esExentoPorEdad(Compareciente comp, LocalDate hoy) {
    if (comp == null || comp.getFechaNacimiento() == null) {
      return false;
    }
    return !comp.getFechaNacimiento().plusYears(65).isAfter(hoy);
  }

  private Fecha fechaCampo(DocumentoExtraidoDTO extraido, String campo) {
    if (extraido == null) {
      return null;
    }
    String val = extraido.datosClave().get(campo);
    if (!StringUtils.hasText(val)) {
      return null;
    }
    return leerFecha(val.trim());
  }

  /**
   * ISO y mes en letras no son ambiguos. Numérica: día/mes (formato ecuatoriano); si el primer
   * número pasa de 12 no hay duda; si ambos son ≤ 12 y distintos, queda ambigua.
   */
  static Fecha leerFecha(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String s = raw.trim();
    Matcher iso = FECHA_ISO.matcher(s);
    if (iso.find()) {
      LocalDate f = fecha(iso.group(1), iso.group(2), iso.group(3));
      if (f != null) {
        return new Fecha(f, false);
      }
    }
    Matcher texto = FECHA_TEXTO.matcher(s);
    if (texto.find()) {
      int mes = mesANumero(texto.group(2));
      LocalDate f = mes > 0 ? fecha(texto.group(3), String.valueOf(mes), texto.group(1)) : null;
      if (f != null) {
        return new Fecha(f, false);
      }
    }
    Matcher num = FECHA_NUM.matcher(s);
    if (num.find()) {
      int a = Integer.parseInt(num.group(1));
      int b = Integer.parseInt(num.group(2));
      if (a > 12 && b <= 12) {
        LocalDate f = fecha(num.group(3), num.group(2), num.group(1));
        return f == null ? null : new Fecha(f, false);
      }
      if (b > 12 && a <= 12) {
        // Solo cabe mes/día: el documento usa el formato al revés.
        LocalDate f = fecha(num.group(3), num.group(1), num.group(2));
        return f == null ? null : new Fecha(f, false);
      }
      LocalDate f = fecha(num.group(3), num.group(2), num.group(1));
      return f == null ? null : new Fecha(f, a != b);
    }
    return null;
  }

  private static LocalDate fecha(String anio, String mes, String dia) {
    try {
      return LocalDate.of(Integer.parseInt(anio), Integer.parseInt(mes), Integer.parseInt(dia));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static int mesANumero(String mes) {
    return switch (mes.toLowerCase(Locale.ROOT).substring(0, Math.min(3, mes.length()))) {
      case "ene", "jan" -> 1;
      case "feb" -> 2;
      case "mar" -> 3;
      case "abr", "apr" -> 4;
      case "may" -> 5;
      case "jun" -> 6;
      case "jul" -> 7;
      case "ago", "aug" -> 8;
      case "sep" -> 9;
      case "oct" -> 10;
      case "nov" -> 11;
      case "dic", "dec" -> 12;
      default -> 0;
    };
  }

  private Map<String, DocumentoExtraidoDTO> indexarPorTipo(List<DocumentoExtraidoDTO> docs) {
    Map<String, DocumentoExtraidoDTO> out = new LinkedHashMap<>();
    for (DocumentoExtraidoDTO d : docs) {
      if (StringUtils.hasText(d.tipoDocumento())) {
        out.putIfAbsent(d.tipoDocumento().trim().toUpperCase(Locale.ROOT), d);
      }
    }
    return out;
  }
}
