package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ValorExtraido;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta.CampoPlantilla;
import com.lexia.api.modules.ia.prompt.ActoVariableCatalog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Consolidación data-driven de las variables de una minuta (TICKET-INT-102 [A]).
 *
 * <p>{@code JSON_Final = JSON_LLM + JSON_BIESS + Overrides_Manuales_UI}: cada capa solo pisa a la
 * anterior cuando trae valor (los overrides sí pueden vaciar un campo a propósito). El resultado se
 * valida contra el catálogo del acto ({@link ActoVariableCatalog}) más los tags reales de la
 * plantilla, y se listan como pendientes los tags vacíos o {@code nodata}.
 */
@Service
public class ExpedienteVariablesService {

  private static final Logger LOG = LoggerFactory.getLogger(ExpedienteVariablesService.class);
  private static final TypeReference<Map<String, String>> MAPA = new TypeReference<>() {};

  public static final String ORIGEN_IA = "IA";
  public static final String ORIGEN_BIESS = "BIESS";
  public static final String ORIGEN_MANUAL = "MANUAL";
  public static final String ORIGEN_AUTO = "AUTO";

  static final String MONTO_LETRAS = "monto_prestamo_letras";

  private static final Locale ES = Locale.forLanguageTag("es");

  private final ActoVariableCatalog catalogo;
  private final DocxMinutaRenderer renderer;
  private final ObjectMapper mapper;

  public ExpedienteVariablesService(
      ActoVariableCatalog catalogo, DocxMinutaRenderer renderer, ObjectMapper mapper) {
    this.catalogo = catalogo;
    this.renderer = renderer;
    this.mapper = mapper;
  }

  /** Variables del acto: catálogo del prompt en su orden + tags de la plantilla no catalogados. */
  public List<String> camposDelActo(MinutaTemplateDescriptor descriptor) {
    LinkedHashSet<String> campos =
        new LinkedHashSet<>(
            catalogo.resolve(descriptor.productCode(), descriptor.templateKind()).campos());
    Set<String> conocidos = new MinutaViviendaData().toTemplateMap().keySet();
    for (String tag : renderer.tags(descriptor)) {
      String campo = MinutaTagAliases.canonico(tag);
      if (conocidos.contains(campo)) {
        campos.add(campo);
      }
    }
    return List.copyOf(campos);
  }

  /**
   * Fusión con precedencia LLM &lt; BIESS &lt; overrides. Devuelve una copia; no muta {@code llm}.
   * {@code monto_prestamo_letras} es derivado: siempre se calcula desde la cifra final y no admite
   * valor del LLM ni override manual.
   */
  public MinutaViviendaData fusionar(
      MinutaViviendaData llm, DatosBiessMinuta biess, Map<String, String> overrides) {
    ObjectNode json = mapper.valueToTree(llm == null ? new MinutaViviendaData() : llm);
    if (biess != null) {
      for (CampoPlantilla campo : CampoPlantilla.values()) {
        pisarSiHayValor(json, campo.tag(), campo.valor(biess));
      }
    }
    if (overrides != null) {
      for (Map.Entry<String, String> e : overrides.entrySet()) {
        String campo = MinutaTagAliases.canonico(e.getKey());
        if (!MONTO_LETRAS.equals(campo)) {
          json.set(campo, TextNode.valueOf(e.getValue() == null ? "" : e.getValue().trim()));
        }
      }
    }
    json.set(
        MONTO_LETRAS, TextNode.valueOf(letras(json.path(CampoPlantilla.MONTO.tag()).asText(""))));
    try {
      return mapper.treeToValue(json, MinutaViviendaData.class);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudieron fusionar las variables de la minuta.");
    }
  }

  /** Estado del JSON final frente al acto: valores (vacío si falta) y lista de pendientes. */
  public VariablesConsolidadas consolidar(
      MinutaTemplateDescriptor descriptor, MinutaViviendaData data) {
    Map<String, Object> map = (data == null ? new MinutaViviendaData() : data).toTemplateMap();
    Map<String, String> variables = new LinkedHashMap<>();
    Map<String, String> etiquetas = new LinkedHashMap<>();
    List<String> pendientes = new ArrayList<>();
    Set<String> enPlantilla = new LinkedHashSet<>();
    for (String tag : renderer.tags(descriptor)) {
      enPlantilla.add(MinutaTagAliases.canonico(tag));
    }
    for (String campo : camposDelActo(descriptor)) {
      Object valor = map.get(campo);
      boolean falta = MinutaViviendaData.isMissing(valor);
      variables.put(campo, falta ? "" : valor.toString());
      etiquetas.put(campo, MinutaViviendaData.etiquetaCorta(campo));
      if (falta && enPlantilla.contains(campo)) {
        pendientes.add(campo);
      }
    }
    return new VariablesConsolidadas(variables, List.copyOf(pendientes), etiquetas);
  }

  /**
   * Capa de la que sale cada variable del acto (MANUAL &gt; BIESS &gt; IA; sin entrada si no tiene
   * valor) y, para las manuales, el valor extraído al que vuelve "Restaurar".
   *
   * @param extraidos capa IA + expediente, sin BIESS ni overrides
   */
  public Origenes origenes(
      MinutaTemplateDescriptor descriptor,
      MinutaViviendaData extraidos,
      DatosBiessMinuta biess,
      Map<String, String> overrides) {
    Map<String, Object> sinOverrides = fusionar(extraidos, biess, Map.of()).toTemplateMap();
    Map<String, String> origenes = new LinkedHashMap<>();
    Map<String, ValorExtraido> valores = new LinkedHashMap<>();
    Map<String, Object> finales =
        overrides == null || overrides.isEmpty()
            ? sinOverrides
            : fusionar(extraidos, biess, overrides).toTemplateMap();
    for (String campo : camposDelActo(descriptor)) {
      if (MONTO_LETRAS.equals(campo)) {
        if (!MinutaViviendaData.isMissing(finales.get(campo))) {
          origenes.put(campo, ORIGEN_AUTO);
        }
        continue;
      }
      Object valor = sinOverrides.get(campo);
      String origen =
          MinutaViviendaData.isMissing(valor)
              ? null
              : StringUtils.hasText(valorBiess(biess, campo)) ? ORIGEN_BIESS : ORIGEN_IA;
      if (overrides != null && overrides.containsKey(campo)) {
        origenes.put(campo, ORIGEN_MANUAL);
        valores.put(campo, new ValorExtraido(origen == null ? "" : valor.toString(), origen));
      } else if (origen != null) {
        origenes.put(campo, origen);
      }
    }
    return new Origenes(origenes, valores);
  }

  /** Quita los overrides de {@code tags} (canónicos o alias) para volver al valor extraído. */
  public void quitarOverrides(Map<String, String> overrides, List<String> tags) {
    if (tags == null) {
      return;
    }
    for (String tag : tags) {
      if (tag != null) {
        overrides.remove(MinutaTagAliases.canonico(tag.trim()));
      }
    }
  }

  /**
   * Normaliza el body del preview: solo tags editables del acto (canónicos o alias), números a texto
   * plano. Las letras del monto son derivadas y se descartan.
   */
  public Map<String, String> normalizarOverrides(
      MinutaTemplateDescriptor descriptor, Map<String, Object> entrada) {
    Map<String, String> out = new LinkedHashMap<>();
    if (entrada == null || entrada.isEmpty()) {
      return out;
    }
    Set<String> permitidos = new LinkedHashSet<>(camposDelActo(descriptor));
    permitidos.remove(MONTO_LETRAS);
    List<String> descartados = new ArrayList<>();
    for (Map.Entry<String, Object> e : entrada.entrySet()) {
      String campo = MinutaTagAliases.canonico(e.getKey() == null ? "" : e.getKey().trim());
      if (!permitidos.contains(campo)) {
        descartados.add(e.getKey());
        continue;
      }
      out.put(campo, aTexto(e.getValue()));
    }
    if (!descartados.isEmpty()) {
      LOG.info(
          "Variables fuera del acto {}:{} ignoradas: {}",
          descriptor.productCode(),
          descriptor.templateKind(),
          descartados);
    }
    return out;
  }

  public Map<String, String> leerOverrides(String json) {
    if (!StringUtils.hasText(json)) {
      return new LinkedHashMap<>();
    }
    try {
      Map<String, String> leidos = mapper.readValue(json, MAPA);
      Map<String, String> out = leidos == null ? new LinkedHashMap<>() : new LinkedHashMap<>(leidos);
      out.keySet().removeIf(k -> MONTO_LETRAS.equals(MinutaTagAliases.canonico(k)));
      return out;
    } catch (JsonProcessingException e) {
      LOG.warn("Overrides de minuta ilegibles: {}", e.getMessage());
      return new LinkedHashMap<>();
    }
  }

  public String escribirOverrides(Map<String, String> overrides) {
    try {
      return mapper.writeValueAsString(overrides == null ? Map.of() : overrides);
    } catch (JsonProcessingException e) {
      throw ApiException.badRequest("No se pudieron guardar las variables de la minuta.");
    }
  }

  private static String valorBiess(DatosBiessMinuta biess, String campo) {
    return CampoPlantilla.porTag(campo).map(c -> c.valor(biess)).orElse(null);
  }

  private static void pisarSiHayValor(ObjectNode json, String campo, String valor) {
    if (StringUtils.hasText(valor)) {
      json.set(campo, TextNode.valueOf(valor.trim()));
    }
  }

  /** Recalcula las letras desde la cifra (flujos que no pasan por {@link #fusionar}). */
  public static void letrasDesdeMonto(MinutaViviendaData data) {
    data.setMontoPrestamoLetras(letras(data.getMontoPrestamo()));
  }

  /**
   * Solo las palabras ("OCHENTA Y CINCO MIL"): las plantillas ya escriben "(Son: … Dólares de los
   * Estados Unidos de América)" alrededor del tag.
   */
  static String letras(String monto) {
    return NumeroALetras.monto(monto).toUpperCase(ES);
  }

  private static String aTexto(Object valor) {
    if (valor == null) {
      return "";
    }
    if (valor instanceof JsonNode node) {
      return node.isNull() ? "" : node.asText("").trim();
    }
    if (valor instanceof Number n) {
      return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
    }
    return valor.toString().trim();
  }

  /**
   * @param variables tag canónico → valor ("" si falta) para todo el acto.
   * @param variablesPendientes tags usados por la plantilla sin valor real.
   * @param etiquetas tag → etiqueta legible para el formulario.
   */
  public record VariablesConsolidadas(
      Map<String, String> variables, List<String> variablesPendientes, Map<String, String> etiquetas) {

    public boolean completo() {
      return variablesPendientes.isEmpty();
    }
  }

  /**
   * @param origenes tag → IA | BIESS | MANUAL (solo los que tienen valor o son manuales).
   * @param valoresExtraidos tag manual → valor sin override.
   */
  public record Origenes(
      Map<String, String> origenes, Map<String, ValorExtraido> valoresExtraidos) {}
}
