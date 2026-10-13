package com.lexia.api.modules.ia.llm;

import java.util.UUID;

/** Último uso del hilo, para el log y para la fila de ingesta. */
public final class IaConsumoContexto {

  public record Uso(UUID expedienteId, String modelo, int promptTokens, int completionTokens, long ms) {}

  private static final ThreadLocal<UUID> EXPEDIENTE = new ThreadLocal<>();
  private static final ThreadLocal<Uso> ULTIMO = new ThreadLocal<>();

  private IaConsumoContexto() {}

  public static void setExpediente(UUID expedienteId) {
    EXPEDIENTE.set(expedienteId);
    ULTIMO.remove();
  }

  public static UUID expediente() {
    return EXPEDIENTE.get();
  }

  public static void anotar(String modelo, int promptTokens, int completionTokens, long ms) {
    ULTIMO.set(new Uso(EXPEDIENTE.get(), modelo, promptTokens, completionTokens, ms));
  }

  public static Uso ultimo() {
    return ULTIMO.get();
  }

  public static void clear() {
    EXPEDIENTE.remove();
  }
}
