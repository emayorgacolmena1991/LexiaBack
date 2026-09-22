package com.lexia.api.modules.expedientes;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;

import java.util.List;

import java.util.Locale;

import org.springframework.stereotype.Component;



@Component

public class EjdRuleBodyParser {



  private final ObjectMapper objectMapper;



  public EjdRuleBodyParser(ObjectMapper objectMapper) {

    this.objectMapper = objectMapper;

  }



  public ParsedRuleBody parse(RuleDef rule) {

    if (rule == null) {

      return ParsedRuleBody.manual();

    }

    String engine = engineFromCode(rule.getCode());

    List<String> keys = defaultCrossKeys();

    if (rule.getBody() == null || rule.getBody().isBlank()) {

      return new ParsedRuleBody(engine, keys);

    }

    try {

      JsonNode root = objectMapper.readTree(rule.getBody());

      if (root.hasNonNull("engine")) {

        engine = root.get("engine").asText("manual").trim().toLowerCase(Locale.ROOT);

      }

      if (root.has("keys") && root.get("keys").isArray()) {

        keys = new ArrayList<>();

        for (JsonNode node : root.get("keys")) {

          if (node.isTextual() && !node.asText().isBlank()) {

            keys.add(normalizeKey(node.asText()));

          }

        }

      }

      return new ParsedRuleBody(engine, keys.isEmpty() ? defaultCrossKeys() : keys);

    } catch (Exception ex) {

      return new ParsedRuleBody(engineFromCode(rule.getCode()), defaultCrossKeys());

    }

  }



  private static String engineFromCode(String code) {

    if (code == null) {

      return "manual";

    }

    return switch (code) {

      case "EJD.V1" -> "document_presence";

      case "EJD.V2" -> "document_integrity";

      case "EJD.V3" -> "document_vigency";

      case "EJD.V4" -> "internal_consistency";

      case "EJD.V5" -> "cross_document";

      case "EJD.V6" -> "human_legal_review";

      case "ECD.V1" -> "document_presence";

      case "ECD.V2" -> "document_integrity";

      case "ECD.V3" -> "document_vigency";

      case "ECD.V4" -> "internal_consistency";

      default -> "manual";

    };

  }



  private static List<String> defaultCrossKeys() {

    return List.of("MATRICULA", "FOLIO");

  }



  private static String normalizeKey(String raw) {

    return raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');

  }



  public record ParsedRuleBody(String engine, List<String> keys) {

    static ParsedRuleBody manual() {

      return new ParsedRuleBody("manual", List.of("MATRICULA", "FOLIO"));

    }

  }

}

