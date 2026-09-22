package com.lexia.api.modules.expedientes;



import com.lexia.api.modules.expedientes.EjdRuleBodyParser.ParsedRuleBody;

import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.LinkedHashSet;

import java.util.List;

import java.util.Locale;

import java.util.Map;

import java.util.Set;

import org.springframework.stereotype.Component;



/** Motor determinístico LEXIA-10 (subset EJD V1–V6). */

@Component

public class LexiaRulesEngine {



  public EvaluationOutcome evaluateInternalConsistency(

      List<ExtractedData> rows, RuleDef rule, ParsedRuleBody body) {

    if (rows.isEmpty()) {

      return EvaluationOutcome.pending(

          "Sin datos extraídos; cargue documentos y extracción antes de consistencia interna.");

    }

    List<String> conflicts = new ArrayList<>();

    Map<String, Set<String>> valuesByDocAndLabel = new LinkedHashMap<>();

    for (ExtractedData row : rows) {

      if (row.getDocumentId() == null || isBlank(row.getFieldLabel()) || isBlank(row.getFieldValue())) {

        continue;

      }

      String key = row.getDocumentId() + "|" + normalizeKey(row.getFieldLabel());

      valuesByDocAndLabel.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(normalizeValue(row.getFieldValue()));

    }

    for (Map.Entry<String, Set<String>> entry : valuesByDocAndLabel.entrySet()) {

      if (entry.getValue().size() > 1) {

        conflicts.add(entry.getKey().substring(entry.getKey().indexOf('|') + 1));

      }

    }

    if (conflicts.isEmpty()) {

      return EvaluationOutcome.pass("Campos internos consistentes por documento.");

    }

    return EvaluationOutcome.fail(

        "Inconsistencia interna en: " + String.join(", ", distinctLabels(conflicts)) + ".");

  }



  public EvaluationOutcome evaluateCrossDocument(

      List<ExtractedData> rows, RuleDef rule, ParsedRuleBody body) {

    if (rows.isEmpty()) {

      return EvaluationOutcome.pending(

          "Sin datos extraídos para comparar entre documentos.");

    }

    List<String> keys = body.keys();

    List<String> problems = new ArrayList<>();

    for (String key : keys) {

      Set<String> values = new LinkedHashSet<>();

      for (ExtractedData row : rows) {

        if (isBlank(row.getFieldLabel()) || isBlank(row.getFieldValue())) {

          continue;

        }

        if (normalizeKey(row.getFieldLabel()).equals(key)) {

          values.add(normalizeValue(row.getFieldValue()));

        }

      }

      if (values.size() > 1) {

        problems.add(key + " (" + String.join(" vs ", values) + ")");

      }

    }

    if (problems.isEmpty()) {

      boolean anyKeyPresent =

          keys.stream()

              .anyMatch(

                  key ->

                      rows.stream()

                          .anyMatch(

                              row ->

                                  !isBlank(row.getFieldLabel())

                                      && normalizeKey(row.getFieldLabel()).equals(key)

                                      && !isBlank(row.getFieldValue())));

      if (!anyKeyPresent) {

        return EvaluationOutcome.observation(

            "No hay valores para "

                + String.join(", ", keys)

                + "; complete extracción o revise manualmente.");

      }

      return EvaluationOutcome.pass("Valores cruzados alineados para claves configuradas.");

    }

    return EvaluationOutcome.fail("Consistencia cruzada: " + String.join("; ", problems) + ".");

  }



  public EvaluationOutcome evaluateHumanLegalReview(RuleDef rule) {

    return EvaluationOutcome.pending(

        "Evaluación jurídica reservada a profesional (LEXIA-10); no se emite PASS automático.");

  }



  private static List<String> distinctLabels(List<String> labels) {

    return new ArrayList<>(new LinkedHashSet<>(labels));

  }



  private static String normalizeKey(String raw) {

    return raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');

  }



  private static String normalizeValue(String raw) {

    return raw.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);

  }



  private static boolean isBlank(String value) {

    return value == null || value.isBlank();

  }



  public record EvaluationOutcome(String result, String evidence) {

    static EvaluationOutcome pass(String evidence) {

      return new EvaluationOutcome("PASS", evidence);

    }



    static EvaluationOutcome fail(String evidence) {

      return new EvaluationOutcome("FAIL", evidence);

    }



    static EvaluationOutcome pending(String evidence) {

      return new EvaluationOutcome("PENDING", evidence);

    }



    static EvaluationOutcome observation(String evidence) {

      return new EvaluationOutcome("OBSERVATION", evidence);

    }

  }

}

