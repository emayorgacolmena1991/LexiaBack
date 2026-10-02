package com.lexia.api.modules.expedientes.coactivas;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** Etapas procesales del juicio coactivo Banecuador. {@code stageCode} = app.process_stage_def.code. */
public enum CoactivaEtapa {
  PREVIA("previa", "Documentación previa"),
  RPV("rpv", "Requerimiento de pago voluntario"),
  OPI_EMITIDA("opi_emitida", "OPI emitida sin notificar"),
  NOTIFICACION_COA("notif_coa", "Notificación COA (OPI notificada)"),
  MEDIDAS_CAUTELARES("medidas", "Medidas cautelares"),
  ESCRITO("escrito", "Atención a escrito / levantamiento"),
  EMBARGO("embargo", "Embargo de bienes"),
  HONORARIOS("honorarios", "Aplicación y honorarios"),
  AVALUO("avaluo", "Avalúo"),
  REMATE("remate", "Remate"),
  CONVENIO("convenio", "Convenio de pago vigente"),
  ARCHIVADO("archivado", "Archivo del proceso");

  private final String stageCode;
  private final String label;

  CoactivaEtapa(String stageCode, String label) {
    this.stageCode = stageCode;
    this.label = label;
  }

  public String stageCode() {
    return stageCode;
  }

  public String label() {
    return label;
  }

  public static Optional<CoactivaEtapa> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (CoactivaEtapa etapa : values()) {
      if (etapa.name().equals(normalized) || etapa.stageCode.equalsIgnoreCase(normalized)) {
        return Optional.of(etapa);
      }
    }
    return Optional.empty();
  }

  public static Optional<CoactivaEtapa> fromStageCode(String stageCode) {
    if (stageCode == null) {
      return Optional.empty();
    }
    for (CoactivaEtapa etapa : values()) {
      if (etapa.stageCode.equalsIgnoreCase(stageCode)) {
        return Optional.of(etapa);
      }
    }
    return Optional.empty();
  }

  /**
   * Interpreta la etapa escrita a mano en actas y bases del banco ("MEDIDAS CAUTELARES",
   * "NOTIFICACION COA", "ORDEN DE PAGO INMEDIATO", ...).
   */
  public static Optional<CoactivaEtapa> fromTextoLibre(String texto) {
    if (texto == null || texto.isBlank()) {
      return Optional.empty();
    }
    Optional<CoactivaEtapa> exact = parse(texto);
    if (exact.isPresent()) {
      return exact;
    }
    String t =
        Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    if (t.contains("archiv") || t.contains("pago total") || t.contains("cancelad")) {
      return Optional.of(ARCHIVADO);
    }
    if (t.contains("convenio") || t.contains("facilidad")) {
      return Optional.of(CONVENIO);
    }
    if (t.contains("remate")) {
      return Optional.of(REMATE);
    }
    if (t.contains("avalu")) {
      return Optional.of(AVALUO);
    }
    if (t.contains("honorario")) {
      return Optional.of(HONORARIOS);
    }
    if (t.contains("escrito") || t.contains("levantamiento")) {
      return Optional.of(ESCRITO);
    }
    if (t.contains("embargo")) {
      return Optional.of(EMBARGO);
    }
    if (t.contains("medida") || t.contains("apremio") || t.contains("ratific")) {
      return Optional.of(MEDIDAS_CAUTELARES);
    }
    if (t.contains("notificacion coa") || t.contains("notificado") || t.contains("citad")) {
      return Optional.of(NOTIFICACION_COA);
    }
    if (t.contains("orden de pago") || t.contains("opi") || t.contains("notificacion")) {
      return Optional.of(OPI_EMITIDA);
    }
    if (t.contains("requerimiento") || t.contains("rpv") || t.contains("pago voluntario")) {
      return Optional.of(RPV);
    }
    if (t.contains("previ") || t.contains("pagare")) {
      return Optional.of(PREVIA);
    }
    return Optional.empty();
  }
}
