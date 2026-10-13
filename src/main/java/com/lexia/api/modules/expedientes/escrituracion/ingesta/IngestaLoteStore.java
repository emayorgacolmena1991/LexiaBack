package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Estado del lote en Postgres. Sobrevive a un reinicio del proceso. */
@Repository
public class IngestaLoteStore {

  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;

  public IngestaLoteStore(EntityManager entityManager, ObjectMapper objectMapper) {
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void guardar(UUID tenantId, UUID loteId, IngestaEstado estado) {
    List<IngestaEstado.Fila> filas = estado.filas() == null ? List.of() : estado.filas();
    int completadas = 0;
    for (IngestaEstado.Fila fila : filas) {
      if ("COMPLETADO".equals(estadoDe(fila))) {
        completadas++;
      }
    }
    entityManager
        .createNativeQuery(
            """
            INSERT INTO app.ingesta_lote (id, tenant_id, estado, total_filas, filas_completadas)
            VALUES (:id, :tenantId, :estado, :total, :hechas)
            ON CONFLICT (id) DO UPDATE SET
              estado = EXCLUDED.estado,
              total_filas = EXCLUDED.total_filas,
              filas_completadas = EXCLUDED.filas_completadas
            """)
        .setParameter("id", loteId)
        .setParameter("tenantId", tenantId)
        .setParameter("estado", estado.fase() == null ? "CREADO" : estado.fase())
        .setParameter("total", filas.size())
        .setParameter("hechas", completadas)
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "DELETE FROM app.ingesta_item WHERE lote_id = :id AND tenant_id = :tenantId")
        .setParameter("id", loteId)
        .setParameter("tenantId", tenantId)
        .executeUpdate();
    int orden = 0;
    for (IngestaEstado.Fila fila : filas) {
      String estadoItem = estadoDe(fila);
      entityManager
          .createNativeQuery(
              """
              INSERT INTO app.ingesta_item
                (id, tenant_id, lote_id, client_row_id, orden, expediente_id, borrador_id,
                 estado, porcentaje_progreso, error_log, detalle)
              VALUES
                (:id, :tenantId, :loteId, :clientRowId, :orden, :expedienteId, :borradorId,
                 :estado, :progreso, :errorLog, CAST(:detalle AS jsonb))
              """)
          .setParameter("id", UUID.randomUUID())
          .setParameter("tenantId", tenantId)
          .setParameter("loteId", loteId)
          .setParameter("clientRowId", fila.clientRowId())
          .setParameter("orden", orden++)
          .setParameter("expedienteId", fila.expedienteId())
          .setParameter("borradorId", fila.borradorId())
          .setParameter("estado", estadoItem)
          .setParameter("progreso", progresoDe(fila, estadoItem))
          .setParameter("errorLog", errorDe(fila))
          .setParameter("detalle", json(fila))
          .executeUpdate();
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public Optional<IngestaEstado> cargar(UUID tenantId, UUID loteId) {
    List<?> fase =
        entityManager
            .createNativeQuery(
                "SELECT estado FROM app.ingesta_lote WHERE id = :id AND tenant_id = :tenantId")
            .setParameter("id", loteId)
            .setParameter("tenantId", tenantId)
            .getResultList();
    if (fase.isEmpty()) {
      return Optional.empty();
    }
    Query items =
        entityManager
            .createNativeQuery(
                """
                SELECT CAST(detalle AS text)
                FROM app.ingesta_item
                WHERE lote_id = :id AND tenant_id = :tenantId
                ORDER BY orden
                """)
            .setParameter("id", loteId)
            .setParameter("tenantId", tenantId);
    List<IngestaEstado.Fila> filas = new ArrayList<>();
    for (Object row : items.getResultList()) {
      filas.add(leer(String.valueOf(row)));
    }
    return Optional.of(new IngestaEstado(String.valueOf(fase.get(0)), filas));
  }

  static String estadoDe(IngestaEstado.Fila fila) {
    if ("ERROR".equals(fila.ocrEstado()) || "ERROR".equals(fila.iaEstado())) {
      return "ERROR";
    }
    if ("EN_PROCESO".equals(fila.iaEstado())) {
      return "ANALIZANDO_IA";
    }
    if ("OK".equals(fila.iaEstado())) {
      return "COMPLETADO";
    }
    if ("PROCESANDO".equals(fila.ocrEstado())) {
      return "OCR";
    }
    if (fila.archivos() != null && !fila.archivos().isEmpty()) {
      return "LISTO".equals(fila.ocrEstado()) ? "OCR" : "SUBIENDO";
    }
    return "CREANDO";
  }

  private static int progresoDe(IngestaEstado.Fila fila, String estado) {
    if (fila.confianza() > 0) {
      return fila.confianza();
    }
    return switch (estado) {
      case "COMPLETADO" -> 100;
      case "ANALIZANDO_IA" -> 75;
      case "OCR" -> 50;
      case "SUBIENDO" -> 25;
      default -> 0;
    };
  }

  private static String errorDe(IngestaEstado.Fila fila) {
    if ("ERROR".equals(fila.iaEstado())) {
      return fila.iaResumen();
    }
    if ("ERROR".equals(fila.ocrEstado()) || "ROJO".equals(fila.semaforo())) {
      return fila.observaciones();
    }
    return null;
  }

  private String json(IngestaEstado.Fila fila) {
    try {
      return objectMapper.writeValueAsString(fila);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudo guardar la fila " + fila.clientRowId() + ".");
    }
  }

  private IngestaEstado.Fila leer(String json) {
    try {
      return objectMapper.readValue(json, IngestaEstado.Fila.class);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("El lote guardado no se puede leer.");
    }
  }
}
