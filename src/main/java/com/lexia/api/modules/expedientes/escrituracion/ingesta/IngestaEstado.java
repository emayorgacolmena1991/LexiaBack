package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import java.util.List;
import java.util.UUID;

/** Foto del lote que se guarda en {@code ingesta_lote} / {@code ingesta_item}. */
public record IngestaEstado(String fase, List<Fila> filas) {

  public record Fila(
      String clientRowId,
      String codigo,
      String productCode,
      String canton,
      String ingestionMode,
      UUID borradorId,
      UUID expedienteId,
      String ocrEstado,
      String semaforo,
      int confianza,
      String observaciones,
      String iaEstado,
      String iaResumen,
      String modeloIa,
      Integer tokens,
      Long iaMs,
      List<Archivo> archivos) {}

  public record Archivo(
      String fileId,
      String nombre,
      String tipo,
      String semaforo,
      int confianza,
      boolean legible,
      String mensaje,
      String texto,
      boolean ocrListo,
      String origen,
      Integer ocrMs) {}
}
