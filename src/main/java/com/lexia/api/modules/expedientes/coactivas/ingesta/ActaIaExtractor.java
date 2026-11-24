package com.lexia.api.modules.expedientes.coactivas.ingesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.FilaActa;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Resultado;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Respaldo con IA para actas cuyo formato no reconoce {@link ActaTableParser} (cabecera con otros
 * nombres, columnas en otro orden, foto sin tabla clara). Nunca empeora el resultado: si la IA
 * falla o devuelve menos filas completas, se queda la lectura por palabras clave.
 */
@Component
public class ActaIaExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(ActaIaExtractor.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Por debajo de esta proporción de filas con juicio y deudor se pide ayuda a la IA. */
  static final double MIN_FILAS_COMPLETAS = 0.5;
  private static final int MAX_CHARS_TABLAS = 100_000;
  private static final int MAX_CHARS_TEXTO = 20_000;

  static final String AVISO_IA =
      "Filas extraídas con IA porque no se reconoció el formato del acta. Revísalas antes de generar expedientes.";
  static final String AVISO_IA_FALLO =
      "No se reconoció el formato del acta y la IA no pudo mejorar la lectura. Revisa las filas o regístralas manualmente.";

  static final String PROMPT =
      """
      Eres un asistente que digitaliza actas de entrega de juicios coactivos de Ecuador.
      Cada acta lista expedientes (una fila por juicio). El formato cambia según el delegado u
      oficina: las columnas pueden tener otros nombres, venir en otro orden, ocupar dos filas de
      cabecera o no tener cabecera.

      Para cada expediente devuelve:
      - juicio: número de juicio/proceso/causa tal cual aparece (ej. 025-2024-00026). Obligatorio.
      - deudor: nombre completo del deudor/coactivado/titular. Obligatorio.
      - cedula: cédula (10 dígitos) o RUC (13 dígitos), solo dígitos; vacío si no hay.
      - operacion: número de operación o crédito; vacío si no hay.
      - oficina: oficina/agencia/sucursal de la fila (no la zonal ni la UEC); vacío si no hay.
      - etapa: etapa o estado reportado del proceso, tal cual; vacío si no hay.
      - fojas: número de fojas como entero; omítelo si no hay.
      Además, uec: la unidad ejecutora (UEC) de la cabecera del acta, si aparece.

      Reglas: no inventes datos ni completes con suposiciones; ignora filas de totales,
      subtotales, firmas y encabezados repetidos; una fila del resultado por juicio.
      Responde solo con JSON: {"uec": "...", "filas": [{"juicio": "...", "deudor": "...", ...}]}
      """;

  private final ObjectProvider<AnalisisDocumentoService> analisis;
  private final boolean habilitado;

  public ActaIaExtractor(
      ObjectProvider<AnalisisDocumentoService> analisis,
      @Value("${lexia.coactivas.acta.ia-respaldo:true}") boolean habilitado) {
    this.analisis = analisis;
    this.habilitado = habilitado;
  }

  /**
   * Devuelve el resultado a usar: el de palabras clave si basta, o el de la IA si lo mejora. Las
   * advertencias del resultado indican qué pasó.
   */
  public Resultado mejorar(Resultado porPalabras, List<List<List<String>>> tablas, String texto) {
    if (!habilitado || !necesitaRespaldo(porPalabras)) {
      return porPalabras;
    }
    AnalisisDocumentoService llm = analisis.getIfAvailable();
    if (llm == null || !llm.isConfigured()) {
      LOG.info("Acta sin formato reconocido y sin proveedor IA configurado");
      return conAviso(porPalabras, AVISO_IA_FALLO);
    }
    String entrada = entrada(tablas, texto);
    long inicio = System.nanoTime();
    ValidacionDocumentoJson raw = llm.extraerActaCoactiva(PROMPT, entrada);
    long ms = (System.nanoTime() - inicio) / 1_000_000;
    if (raw.esError()) {
      LOG.warn("Extracción IA de acta falló ms={}: {}", ms, CoactivaTexto.truncate(raw.error(), 300));
      return conAviso(porPalabras, AVISO_IA_FALLO);
    }
    Optional<Resultado> ia = parsear(raw.json());
    if (ia.isEmpty() || completas(ia.get().filas()) <= completas(porPalabras.filas())) {
      LOG.info(
          "Extracción IA de acta no mejora ms={} filasIa={} filasPalabras={}",
          ms,
          ia.map(r -> r.filas().size()).orElse(0),
          porPalabras.filas().size());
      return conAviso(porPalabras, AVISO_IA_FALLO);
    }
    LOG.info(
        "Extracción IA de acta OK ms={} filas={} (palabras clave: {})",
        ms,
        ia.get().filas().size(),
        porPalabras.filas().size());
    String uec = porPalabras.uec() != null ? porPalabras.uec() : ia.get().uec();
    return new Resultado(ia.get().filas(), uec, List.of(AVISO_IA));
  }

  /** Sin filas, o menos de la mitad con juicio y deudor, o detectadas solo por patrón. */
  static boolean necesitaRespaldo(Resultado resultado) {
    List<FilaActa> filas = resultado.filas();
    if (filas.isEmpty()) {
      return true;
    }
    boolean soloPatron =
        resultado.advertencias().stream().anyMatch(a -> a.contains("sin cabecera reconocible"));
    return soloPatron || completas(filas) < filas.size() * MIN_FILAS_COMPLETAS;
  }

  static int completas(List<FilaActa> filas) {
    return (int) filas.stream().filter(f -> f.juicio() != null && f.deudor() != null).count();
  }

  /** JSON de la IA → filas normalizadas. Vacío si el JSON no sirve. No lanza. */
  static Optional<Resultado> parsear(String json) {
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode root = JSON.readTree(json);
      JsonNode filasNode = root.path("filas");
      if (!filasNode.isArray()) {
        return Optional.empty();
      }
      List<FilaActa> filas = new ArrayList<>();
      int numero = 1;
      for (JsonNode f : filasNode) {
        String juicio = texto(f, "juicio");
        String deudor = texto(f, "deudor");
        if (juicio == null && deudor == null) {
          continue;
        }
        if (deudor != null && CoactivaTexto.claveNombre(deudor).matches("TOTAL.*|SUBTOTAL.*")) {
          continue;
        }
        filas.add(
            new FilaActa(
                numero++,
                texto(f, "oficina"),
                texto(f, "operacion"),
                CoactivaTexto.normalizarJuicio(juicio),
                CoactivaTexto.sanitizarDeudor(deudor),
                texto(f, "cedula"),
                texto(f, "etapa"),
                fojas(f.path("fojas"))));
      }
      return Optional.of(new Resultado(filas, texto(root, "uec"), List.of()));
    } catch (Exception ex) {
      LOG.warn("JSON de acta IA inválido: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  /** Tablas como texto tabulado (una línea por fila) más el inicio del texto OCR para la cabecera. */
  static String entrada(List<List<List<String>>> tablas, String texto) {
    StringBuilder sb = new StringBuilder();
    if (tablas != null) {
      for (int t = 0; t < tablas.size() && sb.length() < MAX_CHARS_TABLAS; t++) {
        sb.append("<tabla n=\"").append(t + 1).append("\">\n");
        for (List<String> row : tablas.get(t)) {
          if (row.stream().allMatch(c -> c == null || c.isBlank())) {
            continue;
          }
          sb.append(String.join("\t", row.stream().map(c -> c == null ? "" : c.replace('\n', ' ')).toList()))
              .append('\n');
        }
        sb.append("</tabla>\n");
      }
    }
    if (texto != null && !texto.isBlank()) {
      sb.append("<texto_ocr>\n").append(CoactivaTexto.truncate(texto, MAX_CHARS_TEXTO)).append("\n</texto_ocr>\n");
    }
    return CoactivaTexto.truncate(sb.toString(), MAX_CHARS_TABLAS + MAX_CHARS_TEXTO + 200);
  }

  private static Resultado conAviso(Resultado r, String aviso) {
    List<String> avisos = new ArrayList<>(r.advertencias());
    avisos.add(aviso);
    return new Resultado(r.filas(), r.uec(), avisos);
  }

  private static String texto(JsonNode node, String campo) {
    JsonNode v = node.path(campo);
    if (v.isMissingNode() || v.isNull()) {
      return null;
    }
    return CoactivaTexto.blankToNull(v.asText());
  }

  private static Integer fojas(JsonNode v) {
    if (v.isInt() || v.isLong()) {
      return v.asInt();
    }
    return v.isTextual() ? CoactivaTexto.parseEntero(v.asText()) : null;
  }
}
