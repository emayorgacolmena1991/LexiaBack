package com.lexia.api.modules.expedientes.caso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "estatus_proceso")
public class EstatusProceso {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(nullable = false, length = 80)
  private String etapa;

  @Column(nullable = false)
  private String texto;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  public static EstatusProceso crear(
      UUID tenantId, UUID caseId, UUID autorId, String etapa, String texto) {
    EstatusProceso row = new EstatusProceso();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.caseId = caseId;
    row.createdBy = autorId;
    row.etapa = etapa;
    row.texto = texto;
    row.createdAt = Instant.now();
    return row;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public String getEtapa() {
    return etapa;
  }

  public String getTexto() {
    return texto;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }
}
