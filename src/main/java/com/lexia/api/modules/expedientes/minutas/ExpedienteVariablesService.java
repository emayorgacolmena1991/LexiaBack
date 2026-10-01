package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.ia.prompt.ActoVariableCatalog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

  /** Fusión con precedencia LLM &lt; BIESS &lt; overrides. Devuelve una copia; no muta {@code llm}. */
  public MinutaViviendaData fusionar(
      MinutaViviendaData llm, DatosBiessMinuta biess, Map<String, String> overrides) {
    ObjectNode json = mapper.valueToTree(llm == null ? new MinutaViviendaData() : llm);
    if (biess != null) {
      String montoAnterior = json.path("monto_prestamo").asText("");
      pisarSiHayValor(json, "monto_prestamo", biess.monto());
      pisarSiHayValor(json, "tasa_interes_inicial", biess.tasa());
      pisarSiHayValor(json, "plazo_credito", biess.plazo());
      pisarSiHayValor(json, "cuota_credito", biess.cuota());
      pisarSiHayValor(json, "apoderado_biess", biess.apoderado());
      if (StringUtils.hasText(biess.monto())
          && !normalizar(montoAnterior).equals(normalizar(biess.monto()))
          && (overrides == null || !overrides.containsKey("monto_prestamo_letras"))) {
        // Un monto en letras que ya no corresponde a la cifra queda pendiente.
        json.set("monto_prestamo_letras", TextNode.valueOf(""));
      }
    }
    if (overrides != null) {
      for (Map.Entry<String, String> e : overrides.entrySet()) {
        String campo = MinutaTagAliases.canonico(e.getKey());
        json.set(campo, TextNode.valueOf(e.getValue() == null ? "" : e.getValue().trim()));
      }
    }
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
   * Normaliza el body del preview: solo tags del acto (canónicos o alias), números a texto plano.
   * Devuelve los descartados para registrarlos.
   */
  public Map<String, String> normalizarOverrides(
      MinutaTemplateDescriptor descriptor, Map<String, Object> entrada) {
    Map<String, String> out = new LinkedHashMap<>();
    if (entrada == null || entrada.isEmpty()) {
      return out;
    }
    Set<String> permitidos = new LinkedHashSet<>(camposDelActo(descriptor));
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
      return leidos == null ? new LinkedHashMap<>() : new LinkedHashMap<>(leidos);
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

  private static void pisarSiHayValor(ObjectNode json, String campo, String valor) {
    if (StringUtils.hasText(valor)) {
      json.set(campo, TextNode.valueOf(valor.trim()));
    }
  }

  private static String normalizar(String s) {
    return s == null ? "" : s.replaceAll("[^0-9.]", "");
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
}
