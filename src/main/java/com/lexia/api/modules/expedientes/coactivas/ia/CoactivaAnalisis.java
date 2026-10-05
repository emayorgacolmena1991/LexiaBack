package com.lexia.api.modules.expedientes.coactivas.ia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Diagnóstico JSON del PDF único. Una fila por corrida; la vigente es la más reciente. */
@Entity
@Table(schema = "app", name = "coactiva_analisis")
public class CoactivaAnalisis implements Persistable<UUID> {

  public static final String ANALIZADO = "ANALIZADO";
  public static final String ERROR = "ERROR";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(name = "archivo_id", nullable = false)
  private UUID archivoId;

  @Column(name = "prompt_id")
  private Long promptId;

  @Column(nullable = false, length = 32)
  private String etapa;

  @Column(nullable = false, length = 16)
  private String estado;

  @Column(name = "porcentaje_completitud")
  private Short porcentajeCompletitud;

  @Column(name = "etapa_detectada", length = 32)
  private String etapaDetectada;

  @Column(nullable = false, columnDefinition = "text")
  private String resultado;

  @Column(columnDefinition = "text")
  private String error;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaAnalisis ok(
      UUID tenantId,
      UUID expedienteId,
      UUID archivoId,
      Long promptId,
      String etapa,
      CoactivaDiagnostico diagnostico) {
    CoactivaAnalisis row = base(tenantId, expedienteId, archivoId, promptId, etapa);
    row.estado = ANALIZADO;
    row.porcentajeCompletitud = (short) diagnostico.porcentajeCompletitud();
    row.etapaDetectada = recortar(diagnostico.etapaNormalizada(), 32);
    row.resultado = diagnostico.json();
    return row;
  }

  public static CoactivaAnalisis error(
      UUID tenantId, UUID expedienteId, UUID archivoId, Long promptId, String etapa, String motivo) {
    CoactivaAnalisis row = base(tenantId, expedienteId, archivoId, promptId, etapa);
    row.estado = ERROR;
    row.resultado = "{}";
    row.error = recortar(motivo, 2000);
    return row;
  }

  private static CoactivaAnalisis base(
      UUID tenantId, UUID expedienteId, UUID archivoId, Long promptId, String etapa) {
    CoactivaAnalisis row = new CoactivaAnalisis();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.expedienteId = expedienteId;
    row.archivoId = archivoId;
    row.promptId = promptId;
    row.etapa = etapa == null ? "*" : etapa;
    row.createdAt = Instant.now();
    return row;
  }

  private static String recortar(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public UUID getArchivoId() {
    return archivoId;
  }

  public String getEtapa() {
    return etapa;
  }

  public String getEstado() {
    return estado;
  }

  public Short getPorcentajeCompletitud() {
    return porcentajeCompletitud;
  }

  public String getEtapaDetectada() {
    return etapaDetectada;
  }

  public String getResultado() {
    return resultado;
  }

  public String getError() {
    return error;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
