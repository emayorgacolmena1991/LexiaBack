package com.lexia.api.modules.ia.ocr;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResultadoCotejoDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCotejo;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoComparacion;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoFuente;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoGrupo;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResumen;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

  /**
   * E04: lee caché consolidada (E03) y coteja campos entre documentos.
   * Las filas de comparación salen siempre del cotejo campo-a-campo (valores reales por fuente).
   * Claude, si está configurado, aporta la observación general / resumen notarial.
   *
   * <p>Parejas comparables (mismas reglas que {@code COTEJO_NOTARIAL_V1} / Claude fallback;
   * no inventa nuevas):
   * <ul>
   *   <li>Identidad: Cédula ↔ Papeleta (y equivalentes de identidad)
   *   <li>Inmueble / Linderos: Avalúo ↔ Historia de Dominio
   * </ul>
   */
@Service
public class OcrCotejoService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrCotejoService.class);

  /** Familias de documento alineadas a las reglas de cotejo notarial existentes. */
  enum DocFamilia {
    IDENTIDAD,
    INMUEBLE,
    OTRO
  }

  /**
   * Formatos soportados:
   * <ul>
   *   <li>{@code === DOCUMENTO: TIPO ===\ntexto} (TICKET-DEV-802)
   *   <li>FE markdown: {@code # Tipo\ntexto} (consolidación OCR paso 3)
   *   <li>FE: {@code [Tipo]\n:\n: texto}
   *   <li>BE legado: {@code Tipo:\n:\n: texto}
   * </ul>
   */
  private static final Pattern SECTION =
      Pattern.compile(
          "(?:===\\s*DOCUMENTO:\\s*([^=\\n]+?)\\s*===\\s*"
              + "|^#+\\s+([^\\n]+?)\\s*$\\n?"
              + "|\\[([^\\]]+)\\]\\s*\\n:\\s*\\n:\\s*"
              + "|([^:\\n\\r\\[=]+):\\s*\\n:\\s*\\n:\\s*)",
          Pattern.MULTILINE);

  /** Orden fijo de la vista principal (máx. 5 grupos con datos). */
  private static final List<GrupoDef> GRUPOS_ORDEN =
      List.of(
          new GrupoDef("identidad", "Identidad"),
          new GrupoDef("inmueble", "Inmueble"),
          new GrupoDef("linderos", "Linderos del inmueble"),
          new GrupoDef("vigencia", "Vigencia documental"),
          new GrupoDef("otros", "Otros relevantes"));

  static final String ESTADO_COINCIDE = "COINCIDE";
  static final String ESTADO_DISCREPANCIA = "DISCREPANCIA";
  /** Compatibilidad con respuestas/tests previos. */
  static final String ESTADO_DIFERENCIA_LEGACY = "DIFERENCIA";
  static final String ESTADO_REVISAR = "REVISAR";
  static final String ESTADO_NO_ENCONTRADO = "NO_ENCONTRADO";

  private static final Pattern FECHA_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern FECHA_NUM =
      Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})");
  private static final Pattern FECHA_TEXTO =
      Pattern.compile(
          "(?i)(\\d{1,2})\\s*(?:de\\s+)?([a-z]{3,})\\s*(?:de\\s+)?(\\d{4})");
  private static final Pattern FECHA_MES_ABR =
      Pattern.compile("(?i)(\\d{1,2})[/\\-\\s]+([a-z]{3})[/\\-\\s]+(\\d{4})");

  private static final Map<String, Integer> MESES = meses();

  private final OcrSessionCacheService cache;
  private final AnalisisDocumentoService analisis;

  public OcrCotejoService(OcrSessionCacheService cache, AnalisisDocumentoService analisis) {
    this.cache = cache;
    this.analisis = analisis;
  }

  public CotejoResponse cotejar(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String id = sessionId.trim();
    String content = cache.getConsolidated(id);
    List<OcrFileResult> results = cache.listResults(id);

    if (content == null && results.isEmpty()) {
      throw ApiException.notFound("Caché OCR no encontrada o expirada para sessionId=" + id);
    }
    if (!StringUtils.hasText(content) && !results.isEmpty()) {
      content = rebuildFromResults(results);
    }
    if (!StringUtils.hasText(content)) {
      return empty(id);
    }

    CotejoResponse porCampos = cotejarPorCampos(id, content, results);
    String observacionIa = observacionDesdeIa(content);
    String observacionGeneral =
        StringUtils.hasText(observacionIa)
            ? observacionIa.trim()
            : observacionDesdeResumen(porCampos.resumen());

    CotejoResumen resumen = porCampos.resumen();
    return new CotejoResponse(
        porCampos.sessionId(),
        new CotejoResumen(
            resumen.total(),
            resumen.coinciden(),
            resumen.diferencias(),
            resumen.noEncontrados(),
            resumen.reglasAplicadas(),
            resumen.observacion(),
            observacionGeneral),
        porCampos.comparaciones(),
        porCampos.grupos());
  }

  private String observacionDesdeIa(String content) {
    if (analisis == null || !analisis.isConfigured()) {
      return null;
    }
    try {
      ExtraccionCotejo cotejo = analisis.cotejarExpediente(content);
      if (cotejo == null || !"OK".equals(cotejo.estado()) || cotejo.resultado() == null) {
        if (cotejo != null && "ERROR".equals(cotejo.estado())) {
          LOG.info("Cotejo notarial no aplicado motivo={} → observación por reglas", cotejo.motivo());
        }
        return null;
      }
      ResultadoCotejoDTO r = cotejo.resultado();
      LOG.info("Cotejo notarial OK estado={} (solo observación general)", r.estado());
      // Preferir lista detallada (una por línea) para el panel Observaciones del FE.
      if (r.observaciones() != null && !r.observaciones().isEmpty()) {
        String joined =
            r.observaciones().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.joining("\n"));
        if (StringUtils.hasText(joined)) {
          return joined;
        }
      }
      if (StringUtils.hasText(r.resumenValidacion())) {
        return r.resumenValidacion().trim();
      }
    } catch (Exception ex) {
      LOG.warn("Cotejo notarial fail err={}", ex.getMessage());
    }
    return null;
  }

  private CotejoResponse cotejarPorCampos(
      String id, String content, List<OcrFileResult> results) {
    List<DocSection> sections = parseSections(content, results);
    if (sections.isEmpty()) {
      return empty(id);
    }

    Map<String, Map<String, String>> valuesByField = new LinkedHashMap<>();

    for (DocSection section : sections) {
      Map<String, String> datos = extractDatos(section);
      for (Map.Entry<String, String> e : datos.entrySet()) {
        String campo = e.getKey();
        String valor = e.getValue();
        if (!StringUtils.hasText(campo)) {
          continue;
        }
        valuesByField
            .computeIfAbsent(campo, k -> new LinkedHashMap<>())
            .put(section.documento(), valor);
      }
    }

    if (valuesByField.isEmpty()) {
      LOG.info("Cotejo sessionId={}: sin datosClave extraídos (docs={})", id, sections.size());
      return empty(id);
    }

    sintetizarNombresCompletos(valuesByField, sections);

    Map<String, ConceptoDef> conceptos = new LinkedHashMap<>();
    Map<String, Map<String, String>> valuesByConcept = new LinkedHashMap<>();

    for (Map.Entry<String, Map<String, String>> entry : valuesByField.entrySet()) {
      ConceptoDef def = resolverConcepto(entry.getKey());
      if (def == null) {
        continue;
      }
      conceptos.putIfAbsent(def.id(), def);
      Map<String, String> porDoc =
          valuesByConcept.computeIfAbsent(def.id(), k -> new LinkedHashMap<>());
      for (Map.Entry<String, String> e : entry.getValue().entrySet()) {
        if (!StringUtils.hasText(e.getValue())) {
          continue;
        }
        porDoc.merge(e.getKey(), e.getValue().trim(), OcrCotejoService::preferirValorMasCompleto);
      }
    }

    Map<String, DocFamilia> familiaPorDoc = familiasPorDocumento(sections);

    List<CotejoComparacion> comparaciones = new ArrayList<>();
    int coinciden = 0;
    int diferencias = 0;
    int noEncontrados = 0;

    for (Map.Entry<String, Map<String, String>> entry : valuesByConcept.entrySet()) {
      ConceptoDef def = conceptos.get(entry.getKey());
      if (def == null) {
        continue;
      }
      Map<String, String> porDocRaw = entry.getValue();

      DocFamilia familia = familiaParaConcepto(def.grupo(), porDocRaw, familiaPorDoc);
      if (familia == null || familia == DocFamilia.OTRO) {
        // Sin pareja válida según reglas existentes: no inventar DISCREPANCIA.
        continue;
      }

      List<String> docsComparables = docsDeFamilia(sections, familiaPorDoc, familia);
      if (docsComparables.size() < 2) {
        // No hay dos fuentes del par (p. ej. solo Cédula sin Papeleta).
        continue;
      }

      Map<String, String> porDoc = new LinkedHashMap<>();
      for (String doc : docsComparables) {
        String v = porDocRaw.get(doc);
        if (StringUtils.hasText(v)) {
          porDoc.put(doc, v.trim());
        }
      }

      List<String> docsConValor = new ArrayList<>(porDoc.keySet());
      Set<String> presentNorm = new LinkedHashSet<>();
      for (String v : porDoc.values()) {
        presentNorm.add(normalizeForConcept(def, v));
      }
      int presentCount = docsConValor.size();
      if (presentCount == 0) {
        continue;
      }

      // Solo un valor y el grupo no admite cotejo parcial → omitir (otros).
      if (presentCount == 1 && "otros".equals(def.grupo())) {
        continue;
      }

      // Fuentes = exactamente el par comparable (valores reales o null si falta).
      List<CotejoFuente> fuentes = new ArrayList<>();
      for (String doc : docsComparables) {
        fuentes.add(new CotejoFuente(doc, porDocRaw.get(doc)));
      }

      String estado;
      String valorResumen;
      String motivo;
      String relacion = String.join(" ↔ ", docsComparables);

      if (presentCount == 1) {
        estado = ESTADO_NO_ENCONTRADO;
        noEncontrados++;
        valorResumen = valorResumenDesdeFuentes(porDoc);
        List<String> faltantes =
            docsComparables.stream().filter(d -> !docsConValor.contains(d)).toList();
        motivo =
            "No se encontró «"
                + def.label()
                + "» en "
                + (faltantes.isEmpty()
                    ? "el resto de documentos del par"
                    : String.join(", ", faltantes))
                + ".";
      } else if (presentNorm.size() > 1) {
        if (esAmbiguedadParcial(def, porDoc)) {
          estado = ESTADO_REVISAR;
          noEncontrados++;
          valorResumen = valorDiferenciaDesdeFuentes(porDoc);
          motivo =
              "Hay equivalencia dudosa en «"
                  + def.label()
                  + "»; conviene verificar manualmente.";
        } else {
          estado = ESTADO_DISCREPANCIA;
          diferencias++;
          valorResumen = valorDiferenciaDesdeFuentes(porDoc);
          motivo = motivoDiscrepanciaConcepto(def, porDoc);
        }
      } else {
        estado = ESTADO_COINCIDE;
        coinciden++;
        valorResumen = valorResumenDesdeFuentes(porDoc);
        motivo =
            "Coincide en "
                + presentCount
                + " documentos ("
                + String.join(", ", docsConValor)
                + ").";
      }

      comparaciones.add(
          new CotejoComparacion(
              def.id(),
              def.label(),
              estado,
              relacion,
              valorResumen,
              motivo,
              fuentes));
    }

    List<CotejoGrupo> grupos = agruparComparaciones(comparaciones);
    int total = comparaciones.size();
    boolean observacion = diferencias + noEncontrados > 0;
    CotejoResumen resumen =
        new CotejoResumen(
            total,
            coinciden,
            diferencias,
            noEncontrados,
            Math.max(grupos.size(), total),
            observacion,
            observacionDesdeConteos(total, coinciden, diferencias, noEncontrados));
    return new CotejoResponse(id, resumen, comparaciones, grupos);
  }

  /**
   * Agrupa comparaciones reales en categorías funcionales para la vista principal.
   * No inventa resultados: solo clasifica y agrega estado/resumen a partir de las filas.
   */
  static List<CotejoGrupo> agruparComparaciones(List<CotejoComparacion> comparaciones) {
    if (comparaciones == null || comparaciones.isEmpty()) {
      return List.of();
    }
    Map<String, List<CotejoComparacion>> porGrupo = new LinkedHashMap<>();
    for (GrupoDef def : GRUPOS_ORDEN) {
      porGrupo.put(def.id(), new ArrayList<>());
    }
    for (CotejoComparacion c : comparaciones) {
      if (c == null) {
        continue;
      }
      String grupoId = clasificarGrupo(c.campo(), c.label());
      porGrupo.computeIfAbsent(grupoId, k -> new ArrayList<>()).add(c);
    }
    List<CotejoGrupo> out = new ArrayList<>();
    for (GrupoDef def : GRUPOS_ORDEN) {
      List<CotejoComparacion> items = porGrupo.getOrDefault(def.id(), List.of());
      if (items.isEmpty()) {
        continue;
      }
      if ("linderos".equals(def.id())) {
        items = refinarComparacionesLinderos(items);
        if (items.isEmpty()) {
          continue;
        }
      }
      String estado = estadoAgregado(items);
      out.add(
          new CotejoGrupo(
              def.id(), def.label(), estado, resumenGrupo(def.id(), items), List.copyOf(items)));
    }
    return out;
  }

  static String clasificarGrupo(String campo, String label) {
    ConceptoDef def = resolverConcepto(campo);
    if (def != null) {
      return def.grupo();
    }
    String key = foldKey(campo);
    String lab = foldKey(label);
    if (esLindero(key, lab)) {
      return "linderos";
    }
    if (esIdentidad(key, lab)) {
      return "identidad";
    }
    if (esVigencia(key, lab)) {
      return "vigencia";
    }
    if (esInmueble(key, lab)) {
      return "inmueble";
    }
    return "otros";
  }

  /**
   * Resuelve un campo crudo (o ya canónico) a un concepto comparable.
   * Devuelve null si el campo no aporta una comparación útil.
   */
  static ConceptoDef resolverConcepto(String campo) {
    if (!StringUtils.hasText(campo)) {
      return null;
    }
    String k = foldKey(campo);

    if ("lindero_norte".equals(campo) || k.equals("linderonorte") || k.equals("norte")) {
      return new ConceptoDef("lindero_norte", "Norte", "linderos", NormMode.LINDERO);
    }
    if ("lindero_sur".equals(campo) || k.equals("linderosur") || k.equals("sur")) {
      return new ConceptoDef("lindero_sur", "Sur", "linderos", NormMode.LINDERO);
    }
    if ("lindero_este".equals(campo)
        || k.equals("linderoeste")
        || k.equals("este")
        || k.equals("oriente")) {
      return new ConceptoDef("lindero_este", "Este", "linderos", NormMode.LINDERO);
    }
    if ("lindero_oeste".equals(campo)
        || k.equals("linderooeste")
        || k.equals("oeste")
        || k.equals("occidente")) {
      return new ConceptoDef("lindero_oeste", "Oeste", "linderos", NormMode.LINDERO);
    }
    if (k.equals("linderos") || k.equals("lindero") || k.equals("colindancias")) {
      return null; // blob genérico: se parte en cardinales al incorporar
    }
    if (esLindero(k, k) && (k.contains("norte") || k.contains("sur") || k.contains("este")
        || k.contains("oeste") || k.contains("oriente") || k.contains("occidente"))) {
      String canon = canonCampo(campo);
      if (!canon.equals(campo) && !foldKey(canon).equals(k)) {
        return resolverConcepto(canon);
      }
    }

    if (esIdentificacionKey(k)) {
      return new ConceptoDef("identificacion", "Número de identificación", "identidad", NormMode.ID);
    }
    if (k.contains("nacionalidad")) {
      return new ConceptoDef("nacionalidad", "Nacionalidad", "identidad", NormMode.TEXTO);
    }
    if (esNombrePersonaKey(k)) {
      return new ConceptoDef("nombreCompleto", "Nombres y apellidos", "identidad", NormMode.TEXTO);
    }

    if (containsAny(k, "fechaemision", "fechadeemision", "emitido", "emision")
        || (k.contains("emision") && k.contains("fecha"))) {
      return new ConceptoDef("fechaEmision", "Fecha de emisión", "vigencia", NormMode.FECHA);
    }
    if (containsAny(
            k,
            "fechavencimiento",
            "fechadevencimiento",
            "vencimiento",
            "caducidad",
            "expira",
            "validohasta",
            "vigentehasta")
        || (k.contains("venc") && k.contains("fecha"))) {
      return new ConceptoDef(
          "fechaVencimiento", "Fecha de vencimiento", "vigencia", NormMode.FECHA);
    }
    if (containsAny(k, "fechainscripcion", "fechadeinscripcion", "inscripcion")) {
      return new ConceptoDef(
          "fechaInscripcion", "Fecha de inscripción", "vigencia", NormMode.FECHA);
    }
    if (k.equals("vigencia")
        || k.equals("vigentedocumental")
        || k.equals("estadodocumento")
        || k.equals("estado")
        || (k.contains("vigencia") && !k.contains("fecha"))) {
      return new ConceptoDef("vigenciaEstado", "Vigencia / estado", "vigencia", NormMode.TEXTO);
    }
    // "fecha" genérica: no cotejar (evita mezclar fechas semánticamente distintas).
    if (k.equals("fecha") || k.equals("fechas")) {
      return null;
    }

    if (containsAny(k, "codigocatastral", "catastr", "clavepredial", "numeropredial")) {
      return new ConceptoDef("codigoCatastral", "Código catastral", "inmueble", NormMode.TEXTO);
    }
    if (containsAny(k, "matricula", "folioreal", "finca")) {
      return new ConceptoDef(
          "matriculaInmobiliaria", "Matrícula inmobiliaria", "inmueble", NormMode.TEXTO);
    }
    if (containsAny(k, "direccion", "ubicacion", "domicilio")) {
      return new ConceptoDef("direccion", "Dirección", "inmueble", NormMode.TEXTO);
    }
    if (k.equals("provincia") || k.endsWith("provincia")) {
      return new ConceptoDef("provincia", "Provincia", "inmueble", NormMode.TEXTO);
    }
    if (k.equals("canton") || k.endsWith("canton")) {
      return new ConceptoDef("canton", "Cantón", "inmueble", NormMode.TEXTO);
    }
    if (k.equals("parroquia") || k.endsWith("parroquia")) {
      return new ConceptoDef("parroquia", "Parroquia", "inmueble", NormMode.TEXTO);
    }
    if (k.equals("manzana") || k.contains("manzana")) {
      return new ConceptoDef("manzana", "Manzana", "inmueble", NormMode.TEXTO);
    }
    if (k.equals("solar") || (k.contains("solar") && !k.contains("solicitante"))) {
      return new ConceptoDef("solar", "Solar", "inmueble", NormMode.TEXTO);
    }
    if (containsAny(k, "descripcionpredio", "descripcioninmueble", "descripciondelpredio", "predio")
        && !k.contains("codigo")
        && !k.contains("area")) {
      return new ConceptoDef("descripcionPredio", "Descripción del predio", "inmueble", NormMode.TEXTO);
    }
    if (containsAny(k, "areatotal", "superficie", "area")
        && !k.contains("areatext")
        && !containsAny(k, "lindero")) {
      return new ConceptoDef("areaTotal", "Área total", "inmueble", NormMode.TEXTO);
    }
    if (esTitularInmuebleKey(k)) {
      return new ConceptoDef("titularInmueble", "Titular", "inmueble", NormMode.TEXTO);
    }

    if (containsAny(k, "numerocertificado", "nrocertificado", "certificado")) {
      return new ConceptoDef("numeroCertificado", "Número de certificado", "otros", NormMode.TEXTO);
    }
    if (containsAny(k, "numerosolicitud", "nrosolicitud", "solicitud")) {
      return new ConceptoDef("numeroSolicitud", "Número de solicitud", "otros", NormMode.TEXTO);
    }
    if (k.contains("repertorio")) {
      return new ConceptoDef("repertorio", "Repertorio", "otros", NormMode.TEXTO);
    }
    if (k.contains("notaria") || k.contains("notario")) {
      return new ConceptoDef("notaria", "Notaría", "otros", NormMode.TEXTO);
    }
    if (containsAny(k, "valortramite", "valordeltramite", "comprobante", "montopago")) {
      return new ConceptoDef(
          k.contains("comprobante") ? "comprobante" : "valorTramite",
          k.contains("comprobante") ? "Comprobante" : "Valor del trámite",
          "otros",
          NormMode.MONTO);
    }

    return null;
  }

  private static boolean esIdentificacionKey(String k) {
    return keyEqualsAny(k, "ci", "dni", "ruc", "nui", "cedula", "identificacion", "pasaporte")
        || containsAny(
            k,
            "cedula",
            "identificacion",
            "documentoidentidad",
            "numerocedula",
            "nrocedula",
            "numeroidentificacion",
            "nui");
  }

  private static boolean esNombrePersonaKey(String k) {
    if (esTitularInmuebleKey(k) && !containsAny(k, "solicitante", "compareciente")) {
      // titular/propietario del predio → inmueble, no identidad
      if (k.equals("titular")
          || k.equals("propietario")
          || k.contains("titularinmueble")
          || k.contains("propietario")) {
        return false;
      }
    }
    return keyEqualsAny(
            k,
            "nombre",
            "nombres",
            "apellido",
            "apellidos",
            "nombrecompleto",
            "compareciente",
            "persona")
        || containsAny(
            k,
            "nombrecompleto",
            "nombresyapellidos",
            "nombreyapellido",
            "solicitantenombre",
            "titularnombre",
            "compareciente",
            "nombres",
            "apellidos")
        || (k.contains("nombre") && !k.contains("archivo") && !k.contains("documento"))
        || (k.contains("apellido"));
  }

  private static boolean esTitularInmuebleKey(String k) {
    return keyEqualsAny(k, "titular", "propietario", "dueno")
        || containsAny(
            k,
            "titularinmueble",
            "titulardelpredio",
            "propietariodelinmueble",
            "propietariopredio",
            "propietario");
  }

  /**
   * Familia objetivo del grupo según reglas de cotejo existentes.
   * Vigencia/otros: solo si los valores pertenecen a una sola familia comparable
   * (no cruzar Cédula con Avalúo).
   */
  static DocFamilia familiaParaConcepto(
      String grupo,
      Map<String, String> porDoc,
      Map<String, DocFamilia> familiaPorDoc) {
    if ("identidad".equals(grupo)) {
      return DocFamilia.IDENTIDAD;
    }
    if ("inmueble".equals(grupo) || "linderos".equals(grupo)) {
      return DocFamilia.INMUEBLE;
    }
    // vigencia / otros: no cruzar familias
    int id = 0;
    int im = 0;
    if (porDoc != null) {
      for (Map.Entry<String, String> e : porDoc.entrySet()) {
        if (!StringUtils.hasText(e.getValue())) {
          continue;
        }
        DocFamilia f = familiaPorDoc.getOrDefault(e.getKey(), DocFamilia.OTRO);
        if (f == DocFamilia.IDENTIDAD) {
          id++;
        } else if (f == DocFamilia.INMUEBLE) {
          im++;
        }
      }
    }
    if (id > 0 && im == 0) {
      return DocFamilia.IDENTIDAD;
    }
    if (im > 0 && id == 0) {
      return DocFamilia.INMUEBLE;
    }
    if (id >= 2 && im < 2) {
      return DocFamilia.IDENTIDAD;
    }
    if (im >= 2 && id < 2) {
      return DocFamilia.INMUEBLE;
    }
    // Valores en familias distintas → no comparable (evita DISCREPANCIA artificial).
    return null;
  }

  static Map<String, DocFamilia> familiasPorDocumento(List<DocSection> sections) {
    Map<String, DocFamilia> out = new LinkedHashMap<>();
    if (sections == null) {
      return out;
    }
    for (DocSection s : sections) {
      if (s == null || !StringUtils.hasText(s.documento())) {
        continue;
      }
      out.put(s.documento(), clasificarFamiliaDocumento(s.tipo(), s.documento()));
    }
    return out;
  }

  static List<String> docsDeFamilia(
      List<DocSection> sections, Map<String, DocFamilia> familiaPorDoc, DocFamilia familia) {
    List<String> out = new ArrayList<>();
    if (sections == null || familia == null) {
      return out;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (DocSection s : sections) {
      if (s == null || !StringUtils.hasText(s.documento())) {
        continue;
      }
      String doc = s.documento();
      if (!seen.add(doc)) {
        continue;
      }
      if (familiaPorDoc.getOrDefault(doc, DocFamilia.OTRO) == familia) {
        out.add(doc);
      }
    }
    return out;
  }

  /**
   * Clasifica el documento por tipo/nombre usando alias ya usados en el dominio
   * (CotejoMotor / prompts COTEJO_NOTARIAL), sin hardcodear nombres de archivo concretos.
   */
  static DocFamilia clasificarFamiliaDocumento(String tipo, String documento) {
    String t = foldKey(tipo);
    String d = foldKey(documento);
    // Quitar extensión típica del nombre de archivo para clasificar por tipo semántico.
    if (d.endsWith("png") || d.endsWith("jpg") || d.endsWith("jpeg") || d.endsWith("pdf")
        || d.endsWith("webp") || d.endsWith("tif") || d.endsWith("tiff")) {
      d = d.replaceAll("(png|jpe?g|pdf|webp|tiff?)$", "");
    }
    String blob = t + d;

    if (esFamiliaIdentidad(blob, t, d)) {
      return DocFamilia.IDENTIDAD;
    }
    if (esFamiliaInmueble(blob, t, d)) {
      return DocFamilia.INMUEBLE;
    }
    return DocFamilia.OTRO;
  }

  private static boolean esFamiliaIdentidad(String blob, String tipo, String doc) {
    return containsAny(
            blob,
            "cedula",
            "papeleta",
            "votacion",
            "documentoidentidad",
            "pasaporte")
        || keyEqualsAny(tipo, "cedula", "papeleta", "pasaporte", "dni", "ci")
        || keyEqualsAny(doc, "cedula", "papeleta", "pasaporte", "dni", "ci")
        || (containsAny(blob, "identidad") && !containsAny(blob, "inmueble", "historia", "avalu"));
  }

  private static boolean esFamiliaInmueble(String blob, String tipo, String doc) {
    return containsAny(
            blob,
            "avaluo",
            "historiadominio",
            "historiadedominio",
            "certificadodetradicion",
            "certificadotradicion",
            "tradicionydominio")
        || (containsAny(blob, "historia") && containsAny(blob, "dominio"))
        || containsAny(blob, "avalu")
        || keyEqualsAny(tipo, "avaluo", "historia", "historiadominio");
  }

  /** Une nombres + apellidos por documento cuando no hay nombreCompleto. */
  static void sintetizarNombresCompletos(
      Map<String, Map<String, String>> valuesByField, List<DocSection> sections) {
    Set<String> docs = new LinkedHashSet<>();
    for (DocSection s : sections) {
      if (s != null && StringUtils.hasText(s.documento())) {
        docs.add(s.documento());
      }
    }
    for (Map<String, String> porDoc : valuesByField.values()) {
      docs.addAll(porDoc.keySet());
    }
    for (String doc : docs) {
      String nombres = null;
      String apellidos = null;
      String completo = null;
      for (Map.Entry<String, Map<String, String>> e : valuesByField.entrySet()) {
        String k = foldKey(e.getKey());
        String v = e.getValue().get(doc);
        if (!StringUtils.hasText(v)) {
          continue;
        }
        if (k.equals("nombrecompleto")
            || k.equals("nombresyapellidos")
            || k.equals("nombreyapellido")
            || k.equals("nombreyapellidos")) {
          completo = preferirValorMasCompleto(completo, v);
        } else if (k.equals("nombres") || k.equals("nombre")) {
          nombres = preferirValorMasCompleto(nombres, v);
        } else if (k.equals("apellidos") || k.equals("apellido")) {
          apellidos = preferirValorMasCompleto(apellidos, v);
        }
      }
      if (StringUtils.hasText(completo)) {
        continue;
      }
      if (!StringUtils.hasText(nombres) && !StringUtils.hasText(apellidos)) {
        continue;
      }
      String merged =
          ((nombres == null ? "" : nombres.trim()) + " " + (apellidos == null ? "" : apellidos.trim()))
              .trim()
              .replaceAll("\\s+", " ");
      if (StringUtils.hasText(merged)) {
        valuesByField
            .computeIfAbsent("nombreCompleto", x -> new LinkedHashMap<>())
            .putIfAbsent(doc, merged);
      }
    }
  }

  static String preferirValorMasCompleto(String a, String b) {
    if (!StringUtils.hasText(a)) {
      return b;
    }
    if (!StringUtils.hasText(b)) {
      return a;
    }
    return a.trim().length() >= b.trim().length() ? a.trim() : b.trim();
  }

  static String normalizeForConcept(ConceptoDef def, String raw) {
    if (!StringUtils.hasText(raw)) {
      return "";
    }
    return switch (def.modo()) {
      case ID -> {
        String digits = raw.replaceAll("\\D", "");
        yield digits.isEmpty() ? normalizeValue(raw) : digits;
      }
      case FECHA -> {
        String fecha = fechaComparable(raw);
        yield fecha != null ? fecha : normalizeValue(raw);
      }
      case LINDERO -> normalizeLindero(raw);
      case MONTO -> {
        String n = raw.replaceAll("[^0-9,\\.]", "");
        yield StringUtils.hasText(n) ? n : normalizeValue(raw);
      }
      case TEXTO -> normalizeValue(raw);
    };
  }

  static String normalizeLindero(String raw) {
    String s = normalizeValue(raw);
    s =
        s.replaceAll("\\b(metros?|mts|mtrs?|mtr)\\b", "m")
            .replaceAll("\\s+", " ")
            .trim();
    return s;
  }

  static String fechaComparable(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String s =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replace(".", "")
            .replaceAll("^(vigente hasta|hasta el|hasta|fecha de|fecha)\\s+", "");
    Matcher iso = FECHA_ISO.matcher(s);
    if (iso.find()) {
      return iso.group(1) + "-" + iso.group(2) + "-" + iso.group(3);
    }
    Matcher abr = FECHA_MES_ABR.matcher(s);
    if (abr.find()) {
      Integer mes = MESES.get(abr.group(2).toLowerCase(Locale.ROOT));
      if (mes != null) {
        return String.format(
            Locale.ROOT,
            "%s-%02d-%02d",
            abr.group(3),
            mes,
            Integer.parseInt(abr.group(1)));
      }
    }
    Matcher num = FECHA_NUM.matcher(s);
    if (num.find()) {
      return String.format(
          Locale.ROOT,
          "%s-%02d-%02d",
          num.group(3),
          Integer.parseInt(num.group(2)),
          Integer.parseInt(num.group(1)));
    }
    Matcher texto = FECHA_TEXTO.matcher(s);
    if (texto.find()) {
      Integer mes = MESES.get(texto.group(2).toLowerCase(Locale.ROOT));
      if (mes != null) {
        return String.format(
            Locale.ROOT,
            "%s-%02d-%02d",
            texto.group(3),
            mes,
            Integer.parseInt(texto.group(1)));
      }
    }
    return null;
  }

  private static boolean esAmbiguedadParcial(ConceptoDef def, Map<String, String> porDoc) {
    if (def.modo() != NormMode.TEXTO || !"nombreCompleto".equals(def.id())) {
      return false;
    }
    List<String> norms =
        porDoc.values().stream()
            .filter(StringUtils::hasText)
            .map(v -> normalizeForConcept(def, v))
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    if (norms.size() != 2) {
      return false;
    }
    String a = norms.get(0);
    String b = norms.get(1);
    return a.contains(b) || b.contains(a);
  }

  static String motivoDiscrepanciaConcepto(ConceptoDef def, Map<String, String> porDoc) {
    return switch (def.id()) {
      case "nombreCompleto" ->
          "Los nombres y apellidos no corresponden a la misma persona.";
      case "identificacion" -> "Los números de identificación no coinciden.";
      case "nacionalidad" -> "La nacionalidad no coincide entre documentos.";
      case "codigoCatastral" -> "El código catastral no coincide entre los documentos.";
      case "matriculaInmobiliaria" -> "Las matrículas inmobiliarias no coinciden.";
      case "direccion" -> "Las direcciones del inmueble no coinciden.";
      case "titularInmueble" -> "El titular del inmueble no coincide entre documentos.";
      case "areaTotal" -> "El área total del predio no coincide.";
      case "lindero_norte", "lindero_sur", "lindero_este", "lindero_oeste" ->
          "El lindero " + def.label() + " no coincide entre documentos.";
      case "fechaEmision" -> "Las fechas de emisión no coinciden.";
      case "fechaVencimiento" -> "Las fechas de vencimiento no coinciden.";
      case "fechaInscripcion" -> "Las fechas de inscripción no coinciden.";
      case "vigenciaEstado" -> "El estado de vigencia no coincide entre documentos.";
      default -> motivoDiscrepancia(porDoc);
    };
  }

  private static Map<String, Integer> meses() {
    Map<String, Integer> map = new LinkedHashMap<>();
    String[] nombres = {
      "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
      "octubre", "noviembre", "diciembre"
    };
    for (int i = 0; i < nombres.length; i++) {
      map.put(nombres[i], i + 1);
    }
    map.put("setiembre", 9);
    map.put("ene", 1);
    map.put("feb", 2);
    map.put("mar", 3);
    map.put("abr", 4);
    map.put("may", 5);
    map.put("jun", 6);
    map.put("jul", 7);
    map.put("ago", 8);
    map.put("sep", 9);
    map.put("sept", 9);
    map.put("oct", 10);
    map.put("nov", 11);
    map.put("dic", 12);
    return Map.copyOf(map);
  }

  private static boolean esLindero(String key, String lab) {
    return containsAny(key, "lindero", "linderos", "colinda", "colindancia")
        || containsAny(lab, "lindero", "linderos", "colinda")
        || keyEqualsAny(key, "norte", "sur", "este", "oeste", "oriente", "occidente")
        || key.startsWith("lindero")
        || key.endsWith("norte")
        || key.endsWith("sur")
        || key.endsWith("este")
        || key.endsWith("oeste");
  }

  private static boolean esIdentidad(String key, String lab) {
    if (keyEqualsAny(
        key,
        "ci",
        "dni",
        "ruc",
        "nombre",
        "nombres",
        "apellido",
        "apellidos",
        "nombrecompleto",
        "identificacion",
        "cedula",
        "pasaporte",
        "compareciente",
        "titular",
        "persona",
        "nacionalidad",
        "estadocivil")) {
      return true;
    }
    return containsAny(
            key,
            "nombre",
            "apellido",
            "identificacion",
            "cedula",
            "pasaporte",
            "compareciente",
            "titular",
            "nacionalidad",
            "estadocivil",
            "documentoidentidad",
            "numerocedula",
            "nrocedula")
        || containsAny(
            lab,
            "nombre",
            "apellido",
            "identificacion",
            "cedula",
            "compareciente",
            "titular",
            "pasaporte");
  }

  private static boolean esVigencia(String key, String lab) {
    return containsAny(
            key,
            "vigencia",
            "vigente",
            "vencimiento",
            "validez",
            "emitido",
            "emision",
            "caducidad",
            "expira",
            "fechaemision",
            "fechavencimiento")
        || containsAny(lab, "vigencia", "vencimiento", "validez", "caducidad")
        || keyEqualsAny(key, "fecha", "fechas");
  }

  private static boolean esInmueble(String key, String lab) {
    return containsAny(
            key,
            "inmueble",
            "predio",
            "matricula",
            "catastr",
            "direccion",
            "ubicacion",
            "domicilio",
            "avaluo",
            "avalúo",
            "area",
            "superficie",
            "metros",
            "parroquia",
            "canton",
            "provincia",
            "propietario",
            "comprador",
            "adquirente",
            "folio",
            "finca",
            "lote",
            "manzana",
            "clavepredial",
            "numeropredial")
        || containsAny(
            lab,
            "inmueble",
            "predio",
            "matricula",
            "direccion",
            "avaluo",
            "avalúo",
            "ubicacion",
            "propietario");
  }

  private static boolean containsAny(String haystack, String... needles) {
    if (!StringUtils.hasText(haystack)) {
      return false;
    }
    for (String n : needles) {
      if (haystack.contains(n)) {
        return true;
      }
    }
    return false;
  }

  private static boolean keyEqualsAny(String key, String... values) {
    if (!StringUtils.hasText(key)) {
      return false;
    }
    for (String v : values) {
      if (key.equals(v)) {
        return true;
      }
    }
    return false;
  }

  private static String foldKey(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "";
    }
    return Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "");
  }

  /** Prioridad: DISCREPANCIA > REVISAR/NO_ENCONTRADO > COINCIDE. */
  static String estadoAgregado(List<CotejoComparacion> items) {
    boolean revisar = false;
    for (CotejoComparacion c : items) {
      if (c == null || !StringUtils.hasText(c.estado())) {
        continue;
      }
      if (esDiscrepancia(c.estado())) {
        return ESTADO_DISCREPANCIA;
      }
      if (ESTADO_REVISAR.equals(c.estado()) || ESTADO_NO_ENCONTRADO.equals(c.estado())) {
        revisar = true;
      }
    }
    return revisar ? ESTADO_REVISAR : ESTADO_COINCIDE;
  }

  static boolean esDiscrepancia(String estado) {
    return ESTADO_DISCREPANCIA.equals(estado) || ESTADO_DIFERENCIA_LEGACY.equals(estado);
  }

  /** Resumen corto orientado a la vista principal (no lista de campos). */
  static String resumenGrupo(String grupoId, List<CotejoComparacion> items) {
    if (items == null || items.isEmpty()) {
      return "Sin datos comparables.";
    }
    if ("linderos".equals(grupoId)) {
      int n = items.size();
      long disc = items.stream().filter(c -> c != null && esDiscrepancia(c.estado())).count();
      long revisar =
          items.stream()
              .filter(
                  c ->
                      c != null
                          && (ESTADO_REVISAR.equals(c.estado())
                              || ESTADO_NO_ENCONTRADO.equals(c.estado())))
              .count();
      if (disc > 0) {
        return disc == 1
            ? "1 de " + n + " linderos presentan diferencias"
            : disc + " de " + n + " linderos presentan diferencias";
      }
      if (revisar > 0) {
        return revisar == 1
            ? "1 lindero requiere revisión"
            : revisar + " linderos requieren revisión";
      }
      return n == 1
          ? "El lindero coincide entre documentos"
          : "Los " + n + " linderos coinciden entre documentos";
    }
    String estado = estadoAgregado(items);
    if (esDiscrepancia(estado)) {
      return switch (grupoId == null ? "" : grupoId) {
        case "identidad" -> "Se detectaron diferencias en identidad.";
        case "inmueble" -> "Se detectaron diferencias en datos del inmueble.";
        case "vigencia" -> "Se detectaron diferencias en vigencia documental.";
        case "otros" -> "Se detectaron diferencias en datos complementarios.";
        default -> resumenGrupoPorConteos(items);
      };
    }
    if (ESTADO_REVISAR.equals(estado) || ESTADO_NO_ENCONTRADO.equals(estado)) {
      return switch (grupoId == null ? "" : grupoId) {
        case "identidad" -> "Hay datos de identidad incompletos o ambiguos.";
        case "inmueble" -> "Hay datos del inmueble incompletos o ambiguos.";
        case "vigencia" -> "Hay fechas o estados de vigencia por revisar.";
        case "otros" -> "Hay datos complementarios por revisar.";
        default -> resumenGrupoPorConteos(items);
      };
    }
    return switch (grupoId == null ? "" : grupoId) {
      case "identidad" -> "Los datos de identidad coinciden entre documentos.";
      case "inmueble" -> "Los datos del inmueble coinciden entre documentos.";
      case "vigencia" -> "Las vigencias documentales coinciden.";
      case "otros" -> "Los datos complementarios coinciden.";
      default -> resumenGrupoPorConteos(items);
    };
  }

  /** @deprecated use {@link #resumenGrupo(String, List)} */
  static String resumenGrupo(List<CotejoComparacion> items) {
    return resumenGrupo(null, items);
  }

  static String resumenGrupoPorConteos(List<CotejoComparacion> items) {
    int n = items.size();
    long coinciden =
        items.stream().filter(c -> c != null && ESTADO_COINCIDE.equals(c.estado())).count();
    long diferencias =
        items.stream().filter(c -> c != null && esDiscrepancia(c.estado())).count();
    long revisar =
        items.stream()
            .filter(
                c ->
                    c != null
                        && (ESTADO_REVISAR.equals(c.estado())
                            || ESTADO_NO_ENCONTRADO.equals(c.estado())))
            .count();
    if (diferencias > 0) {
      return diferencias == 1
          ? "1 diferencia detectada (" + n + " campos cotejados)"
          : diferencias + " diferencias detectadas (" + n + " campos cotejados)";
    }
    if (revisar > 0) {
      return revisar == 1
          ? "1 campo requiere revisión"
          : revisar + " campos requieren revisión";
    }
    return coinciden == 1
        ? "1 campo coincide entre documentos"
        : coinciden + " campos coinciden entre documentos";
  }

  static String previewValoresGrupo(List<CotejoComparacion> items) {
    if (items == null || items.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (CotejoComparacion c : items) {
      if (c == null) {
        continue;
      }
      String valor = primerValorVisible(c);
      if (!StringUtils.hasText(valor)) {
        continue;
      }
      String etiqueta =
          StringUtils.hasText(c.label()) ? c.label().trim() : labelForCampo(c.campo());
      parts.add(etiqueta + ": " + truncar(valor, 56));
      if (parts.size() >= 4) {
        break;
      }
    }
    return parts.isEmpty() ? null : String.join(" · ", parts);
  }

  private static String primerValorVisible(CotejoComparacion c) {
    if (StringUtils.hasText(c.valor())) {
      return c.valor().trim();
    }
    if (c.fuentes() == null) {
      return null;
    }
    for (CotejoFuente f : c.fuentes()) {
      if (f != null && StringUtils.hasText(f.valor())) {
        return f.valor().trim();
      }
    }
    return null;
  }

  private static String valorResumenDesdeFuentes(Map<String, String> porDoc) {
    return porDoc.values().stream().filter(StringUtils::hasText).findFirst().orElse(null);
  }

  private static String valorDiferenciaDesdeFuentes(Map<String, String> porDoc) {
    List<String> distintos =
        porDoc.values().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .limit(2)
            .toList();
    if (distintos.isEmpty()) {
      return null;
    }
    if (distintos.size() == 1) {
      return distintos.get(0);
    }
    return truncar(distintos.get(0), 40) + " ≠ " + truncar(distintos.get(1), 40);
  }

  static String motivoDiscrepancia(Map<String, String> porDoc) {
    List<String> partes = new ArrayList<>();
    for (Map.Entry<String, String> e : porDoc.entrySet()) {
      if (!StringUtils.hasText(e.getValue())) {
        continue;
      }
      partes.add(e.getKey() + ": «" + truncar(e.getValue().trim(), 48) + "»");
      if (partes.size() >= 4) {
        break;
      }
    }
    if (partes.size() < 2) {
      return "Hay valores distintos entre documentos.";
    }
    return "Discrepancia entre documentos — " + String.join(" ≠ ", partes);
  }

  private static String truncar(String raw, int max) {
    String s = raw.trim().replaceAll("\\s+", " ");
    if (s.length() <= max) {
      return s;
    }
    return s.substring(0, Math.max(0, max - 1)).trim() + "…";
  }

  /**
   * Si hay Norte/Sur/Este/Oeste, oculta el blob genérico "linderos" y ordena cardinales.
   */
  static List<CotejoComparacion> refinarComparacionesLinderos(List<CotejoComparacion> items) {
    boolean tieneCardinal =
        items.stream().anyMatch(c -> c != null && esCampoCardinalLindero(c.campo()));
    List<CotejoComparacion> filtrados = new ArrayList<>();
    for (CotejoComparacion c : items) {
      if (c == null) {
        continue;
      }
      if (tieneCardinal && esCampoLinderoGenerico(c.campo())) {
        continue;
      }
      filtrados.add(c);
    }
    filtrados.sort(
        (a, b) ->
            Integer.compare(ordenLindero(a.campo()), ordenLindero(b.campo())));
    return filtrados;
  }

  private static boolean esCampoCardinalLindero(String campo) {
    String k = foldKey(campo);
    return k.contains("norte")
        || k.contains("sur")
        || k.contains("este")
        || k.contains("oeste")
        || k.contains("oriente")
        || k.contains("occidente");
  }

  private static boolean esCampoLinderoGenerico(String campo) {
    String k = foldKey(campo);
    return "linderos".equals(k) || "lindero".equals(k) || "colindancias".equals(k);
  }

  private static int ordenLindero(String campo) {
    String k = foldKey(campo);
    if (k.contains("norte")) {
      return 1;
    }
    if (k.contains("sur")) {
      return 2;
    }
    if (k.contains("este") || k.contains("oriente")) {
      return 3;
    }
    if (k.contains("oeste") || k.contains("occidente")) {
      return 4;
    }
    return 50;
  }

  private Map<String, String> extractDatos(DocSection section) {
    Map<String, String> out = new LinkedHashMap<>();
    if (!StringUtils.hasText(section.texto())) {
      return out;
    }
    if (analisis != null && analisis.isConfigured()) {
      try {
        ExtraccionDocumento ext = analisis.extraerDatosClave(section.texto(), section.tipo());
        if (ext != null && ext.datos() != null && ext.datos().datosClave() != null) {
          Map<String, String> planos = new LinkedHashMap<>();
          aplanarDatos("", ext.datos().datosClave(), planos);
          for (Map.Entry<String, String> e : planos.entrySet()) {
            incorporarCampo(out, e.getKey(), e.getValue());
          }
        }
      } catch (Exception ex) {
        LOG.warn("Cotejo extract fail tipo={} err={}", section.tipo(), ex.getMessage());
      }
    }
    return out;
  }

  /**
   * Aplana mapas/listas anidados (p.ej. {@code linderos: {norte, sur}}) a claves string con valor
   * legible. Sin esto, {@code String.valueOf(Map)} produce textos inútiles o vacíos en el FE.
   */
  static void aplanarDatos(String prefijo, Object valor, Map<String, String> out) {
    if (valor == null) {
      return;
    }
    if (valor instanceof Map<?, ?> map) {
      if (map.isEmpty() && StringUtils.hasText(prefijo)) {
        return;
      }
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() == null) {
          continue;
        }
        String clave = String.valueOf(entry.getKey()).trim();
        String siguiente = prefijo.isEmpty() ? clave : prefijo + "_" + clave;
        aplanarDatos(siguiente, entry.getValue(), out);
      }
      return;
    }
    if (valor instanceof Iterable<?> iterable && !(valor instanceof CharSequence)) {
      List<String> partes = new ArrayList<>();
      for (Object item : iterable) {
        if (item instanceof Map<?, ?> || (item instanceof Iterable<?> && !(item instanceof CharSequence))) {
          aplanarDatos(prefijo, item, out);
        } else if (item != null && StringUtils.hasText(String.valueOf(item))) {
          partes.add(String.valueOf(item).trim());
        }
      }
      if (!partes.isEmpty() && StringUtils.hasText(prefijo)) {
        out.putIfAbsent(prefijo, String.join("; ", partes));
      }
      return;
    }
    if (!StringUtils.hasText(prefijo)) {
      return;
    }
    String texto = textoPlanoDato(valor);
    if (StringUtils.hasText(texto) && !"null".equalsIgnoreCase(texto)) {
      out.putIfAbsent(prefijo, texto);
    }
  }

  private static String textoPlanoDato(Object valor) {
    if (valor instanceof Number number) {
      return number.toString();
    }
    if (valor instanceof Boolean bool) {
      return bool.toString();
    }
    return String.valueOf(valor).trim();
  }

  /** Normaliza claves de lindero y parte textos compuestos "Norte: … Sur: …". */
  static void incorporarCampo(Map<String, String> out, String rawKey, String rawValor) {
    if (!StringUtils.hasText(rawKey) || !StringUtils.hasText(rawValor)) {
      return;
    }
    String valor = rawValor.trim();
    if (!StringUtils.hasText(valor) || "{}".equals(valor) || "[]".equals(valor)) {
      return;
    }
    String campo = canonCampo(rawKey);
    Map<String, String> linderos = parseLinderosCompuestos(valor);
    if (!linderos.isEmpty() && (esCampoLinderoGenerico(campo) || esLindero(foldKey(rawKey), foldKey(rawKey)))) {
      for (Map.Entry<String, String> e : linderos.entrySet()) {
        out.putIfAbsent(e.getKey(), e.getValue());
      }
      return;
    }
    if (linderos.size() >= 2) {
      for (Map.Entry<String, String> e : linderos.entrySet()) {
        out.putIfAbsent(e.getKey(), e.getValue());
      }
      return;
    }
    out.putIfAbsent(campo, valor);
  }

  static String canonCampo(String rawKey) {
    String k = foldKey(rawKey);
    if (!StringUtils.hasText(k)) {
      return rawKey.trim();
    }
    // Oeste ANTES que Este: "oeste" contiene la subcadena "este".
    if (k.equals("oeste")
        || k.equals("occidente")
        || k.equals("linderooeste")
        || k.equals("linderooccidente")
        || k.equals("linderosoeste")
        || k.equals("linderosoccidente")
        || k.endsWith("oeste")
        || k.endsWith("occidente")
        || ((k.contains("lindero") || k.contains("colinda"))
            && (k.contains("oeste") || k.contains("occidente")))) {
      return "lindero_oeste";
    }
    if (k.equals("este")
        || k.equals("oriente")
        || k.equals("linderoeste")
        || k.equals("linderooriente")
        || k.equals("linderoseste")
        || k.equals("linderosoriente")
        || k.endsWith("este")
        || k.endsWith("oriente")
        || ((k.contains("lindero") || k.contains("colinda"))
            && (k.contains("este") || k.contains("oriente"))
            && !k.contains("oeste")
            && !k.contains("occidente"))) {
      return "lindero_este";
    }
    if (k.equals("norte")
        || k.equals("linderonorte")
        || k.equals("linderosnorte")
        || k.equals("colindancenorte")
        || k.endsWith("norte")
        || ((k.contains("lindero") || k.contains("colinda")) && k.contains("norte"))) {
      return "lindero_norte";
    }
    if (k.equals("sur")
        || k.equals("linderosur")
        || k.equals("linderossur")
        || k.endsWith("sur") && !k.contains("superficie")
        || ((k.contains("lindero") || k.contains("colinda"))
            && k.contains("sur")
            && !k.contains("superficie"))) {
      return "lindero_sur";
    }
    if (k.equals("linderos") || k.equals("lindero") || k.equals("colindancias") || k.equals("colindancia")) {
      return "linderos";
    }
    return rawKey.trim().replace(' ', '_');
  }

  private static final Pattern LINDERO_PART =
      Pattern.compile(
          "(?i)\\b(norte|sur|este|oeste|oriente|occidente)\\b\\s*[:\\-–—]?\\s*(.+?)(?=\\s*\\b(?:norte|sur|este|oeste|oriente|occidente)\\b\\s*[:\\-–—]?\\s*|$)");

  /**
   * Parte un texto de linderos en cardinales cuando el OCR/LLM los dejó en un solo string.
   */
  static Map<String, String> parseLinderosCompuestos(String raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (!StringUtils.hasText(raw)) {
      return out;
    }
    String texto = raw.trim();
    Matcher m = LINDERO_PART.matcher(texto);
    while (m.find()) {
      String cardinal = m.group(1).trim();
      String valor = m.group(2).trim().replaceAll("[;|,]+$", "").trim();
      if (!StringUtils.hasText(valor) || valor.length() < 2) {
        continue;
      }
      out.putIfAbsent(canonCampo(cardinal), valor);
    }
    return out;
  }

  static String labelForCampo(String campo) {
    String k = foldKey(campo);
    if ("lindero_norte".equals(campo) || "linderonorte".equals(k) || k.equals("norte")) {
      return "Lindero Norte";
    }
    if ("lindero_sur".equals(campo) || "linderosur".equals(k) || k.equals("sur")) {
      return "Lindero Sur";
    }
    if ("lindero_este".equals(campo) || "linderoeste".equals(k) || k.equals("este") || k.equals("oriente")) {
      return "Lindero Este";
    }
    if ("lindero_oeste".equals(campo)
        || "linderooeste".equals(k)
        || k.equals("oeste")
        || k.equals("occidente")) {
      return "Lindero Oeste";
    }
    if ("linderos".equals(k) || "lindero".equals(k)) {
      return "Linderos";
    }
    return humanLabel(campo);
  }

  static String observacionDesdeResumen(CotejoResumen r) {
    if (r == null) {
      return "No hay datos comparables entre los documentos de esta sesión.";
    }
    if (StringUtils.hasText(r.observacionGeneral())) {
      return r.observacionGeneral().trim();
    }
    return observacionDesdeConteos(
        r.total(), r.coinciden(), r.diferencias(), r.noEncontrados());
  }

  static String observacionDesdeConteos(
      int total, int coinciden, int diferencias, int noEncontrados) {
    if (total <= 0) {
      return "No hay datos comparables entre los documentos de esta sesión.";
    }
    int issues = diferencias + noEncontrados;
    if (issues == 0) {
      return "Los datos equivalentes coinciden entre los documentos procesados ("
          + coinciden
          + " reglas aprobadas).";
    }
    if (issues == 1) {
      return "El cotejo encontró una observación que requiere revisión.";
    }
    return "El cotejo encontró "
        + issues
        + " observaciones que requieren revisión ("
        + diferencias
        + " diferencias, "
        + noEncontrados
        + " sin hallazgo completo).";
  }

  static List<DocSection> parseSections(String content, List<OcrFileResult> results) {
    List<DocSection> sections = new ArrayList<>();
    Matcher m = SECTION.matcher(content);
    List<int[]> ranges = new ArrayList<>();
    List<String> tipos = new ArrayList<>();
    while (m.find()) {
      String tipo = firstNonBlank(m.group(1), m.group(2), m.group(3), m.group(4));
      tipos.add(tipo != null ? tipo : "DOCUMENTO");
      ranges.add(new int[] {m.start(), m.end()});
    }
    if (ranges.isEmpty()) {
      sections.add(new DocSection("DOCUMENTO", "DOCUMENTO", content.trim()));
      return sections;
    }
    for (int i = 0; i < ranges.size(); i++) {
      int textStart = ranges.get(i)[1];
      int textEnd = i + 1 < ranges.size() ? ranges.get(i + 1)[0] : content.length();
      String texto = content.substring(textStart, textEnd).trim();
      String tipo = tipos.get(i);
      String documento = resolveDocName(tipo, i, results);
      sections.add(new DocSection(documento, tipo, texto));
    }
    return sections;
  }

  private static String resolveDocName(String tipo, int index, List<OcrFileResult> results) {
    if (results != null) {
      for (OcrFileResult r : results) {
        if (r == null) {
          continue;
        }
        String rTipo = r.tipoDocumento() == null ? "" : r.tipoDocumento().trim();
        if (rTipo.equalsIgnoreCase(tipo) || tipo.equalsIgnoreCase("[" + rTipo + "]")) {
          if (StringUtils.hasText(r.fileName())) {
            return r.fileName().trim();
          }
          if (StringUtils.hasText(rTipo)) {
            return rTipo;
          }
        }
      }
      if (index >= 0 && index < results.size()) {
        OcrFileResult r = results.get(index);
        if (r != null && StringUtils.hasText(r.fileName())) {
          return r.fileName().trim();
        }
        if (r != null && StringUtils.hasText(r.tipoDocumento())) {
          return r.tipoDocumento().trim();
        }
      }
    }
    return StringUtils.hasText(tipo) ? tipo : ("Documento " + (index + 1));
  }

  private static String rebuildFromResults(List<OcrFileResult> results) {
    StringBuilder sb = new StringBuilder();
    for (OcrFileResult r : results) {
      if (r == null || !r.legible()) {
        continue;
      }
      String tipo = StringUtils.hasText(r.tipoDocumento()) ? r.tipoDocumento() : "DOCUMENTO";
      sb.append("=== DOCUMENTO: ").append(tipo).append(" ===\n");
      sb.append(r.textoExtraido() == null ? "" : r.textoExtraido().trim());
      sb.append("\n\n");
    }
    return sb.toString().trim();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String v : values) {
      if (StringUtils.hasText(v)) {
        return v.trim();
      }
    }
    return null;
  }

  private static CotejoResponse empty(String sessionId) {
    String obs = observacionDesdeConteos(0, 0, 0, 0);
    return new CotejoResponse(
        sessionId, new CotejoResumen(0, 0, 0, 0, 0, false, obs), List.of(), List.of());
  }

  static String normalizeValue(String raw) {
    String s =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s._\\-/]+", " ")
            .trim();
    return s;
  }

  static String humanLabel(String campo) {
    String spaced =
        campo.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim();
    if (!StringUtils.hasText(spaced)) {
      return campo;
    }
    return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
  }

  record DocSection(String documento, String tipo, String texto) {}

  private record GrupoDef(String id, String label) {}

  enum NormMode {
    TEXTO,
    ID,
    FECHA,
    LINDERO,
    MONTO
  }

  record ConceptoDef(String id, String label, String grupo, NormMode modo) {}
}
