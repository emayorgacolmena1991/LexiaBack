package com.lexia.api.modules.expedientes.proceso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "template_def")
public class TemplateDef {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false)
  private int version;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false, length = 8)
  private String vertical;

  @Column
  private String body;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public int getVersion() {
    return version;
  }

  public String getName() {
    return name;
  }

  public String getVertical() {
    return vertical;
  }

  public String getBody() {
    return body;
  }

  public String getStatus() {
    return status;
  }
}
