package com.lexia.api.modules.expedientes.escrituracion.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "app", name = "title_study")
public class TitleStudy {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "writing_file_id", nullable = false)
  private UUID writingFileId;

  @Column(nullable = false, length = 32)
  private String status = "PENDING";

  @Column(columnDefinition = "text")
  private String summary;

  /** Lo que la IA verificó como correcto. Solo lectura en el cotejo; nunca es observación. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> validaciones;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static TitleStudy create(UUID tenantId, UUID writingFileId) {
    TitleStudy study = new TitleStudy();
    study.id = UUID.randomUUID();
    study.tenantId = tenantId;
    study.writingFileId = writingFileId;
    study.status = "PENDING";
    Instant now = Instant.now();
    study.createdAt = now;
    study.updatedAt = now;
    return study;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWritingFileId() {
    return writingFileId;
  }

  public String getStatus() {
    return status;
  }

  public String getSummary() {
    return summary;
  }

  public List<String> getValidaciones() {
    return validaciones == null ? List.of() : validaciones;
  }

  public void registrarValidaciones(List<String> validaciones) {
    this.validaciones =
        validaciones == null
            ? null
            : validaciones.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    this.updatedAt = Instant.now();
  }

  public void applyResult(String status, String summary) {
    this.status = status;
    this.summary = summary;
    this.updatedAt = Instant.now();
  }
}
