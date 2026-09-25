package com.lexia.api.modules.expedientes.escrituracion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "title_observation")
public class TitleObservation {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "title_study_id", nullable = false)
  private UUID titleStudyId;

  @Column(nullable = false, columnDefinition = "text")
  private String detail;

  @Column(nullable = false, length = 32)
  private String status = "OPEN";

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static TitleObservation create(UUID tenantId, UUID titleStudyId, String detail) {
    TitleObservation obs = new TitleObservation();
    obs.id = UUID.randomUUID();
    obs.tenantId = tenantId;
    obs.titleStudyId = titleStudyId;
    obs.detail = detail;
    obs.status = "OPEN";
    obs.createdAt = Instant.now();
    return obs;
  }

  public UUID getId() {
    return id;
  }

  public String getDetail() {
    return detail;
  }

  public String getStatus() {
    return status;
  }

  public void resolve() {
    this.status = "RESOLVED";
  }
}
