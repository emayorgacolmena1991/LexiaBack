package com.lexia.api.modules.ia.llm;

import com.lexia.api.modules.auth.AuthContext;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IaConsumoLog {

  private static final Logger LOG = LoggerFactory.getLogger(IaConsumoLog.class);

  private final EntityManager entityManager;

  public IaConsumoLog(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional
  public void registrar(String modelo, int promptTokens, int completionTokens, long ms) {
    if (AuthContext.get() == null || AuthContext.get().tenantId() == null || modelo == null) {
      return;
    }
    IaConsumoContexto.anotar(modelo, promptTokens, completionTokens, ms);
    try {
      entityManager
          .createNativeQuery(
              """
              INSERT INTO app.ia_log_consumo
                (id, tenant_id, expediente_id, modelo_usado, prompt_tokens, completion_tokens,
                 costo_estimado, tiempo_respuesta_ms, etapa)
              VALUES (:id, :tenantId, :expedienteId, :modelo, :prompt, :completion, :costo, :ms, :etapa)
              """)
          .setParameter("id", UUID.randomUUID())
          .setParameter("tenantId", AuthContext.get().tenantId())
          .setParameter("expedienteId", IaConsumoContexto.expediente())
          .setParameter("modelo", modelo)
          .setParameter("prompt", promptTokens)
          .setParameter("completion", completionTokens)
          .setParameter("costo", costo(modelo, promptTokens, completionTokens))
          .setParameter("ms", ms)
          .setParameter("etapa", IaConsumoContexto.etapa())
          .executeUpdate();
    } catch (RuntimeException ex) {
      LOG.warn("No se pudo guardar ia_log_consumo: {}", ex.getMessage());
    }
    LOG.info(
        "ia-consumo modelo={} promptTokens={} completionTokens={} ms={} expediente={}",
        modelo,
        promptTokens,
        completionTokens,
        ms,
        IaConsumoContexto.expediente());
  }

  /** USD aproximados por millón. Sirve para comparar, no es la factura. */
  static double costo(String modelo, int prompt, int completion) {
    String nombre = modelo.toLowerCase();
    double in = nombre.contains("haiku") || nombre.contains("flash") ? 0.25 : 3.0;
    double out = nombre.contains("haiku") || nombre.contains("flash") ? 1.25 : 15.0;
    return (prompt * in + completion * out) / 1_000_000d;
  }
}
