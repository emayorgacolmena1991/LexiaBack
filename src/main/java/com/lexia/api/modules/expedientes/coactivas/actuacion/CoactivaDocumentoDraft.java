package com.lexia.api.modules.expedientes.coactivas.actuacion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "app", name = "coactiva_documento_draft")
public class CoactivaDocumentoDraft {

  public static final String BORRADOR = "BORRADOR";
  public static final String LISTO = "LISTO";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(name = "plantilla_id", nullable = false)
  private UUID plantillaId;

  @Column(name = "actuacion_tipo", nullable = false, length = 64)
  private String actuacionTipo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String payload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "datos_extraidos", columnDefinition = "jsonb")
  private String datosExtraidos;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String overrides;

  @Column(name = "storage_path", length = 1024)
  private String storagePath;

  @Column(name = "variables_pendientes_count", nullable = false)
  private int variablesPendientesCount;

  @Column(nullable = false, length = 24)
  private String status = BORRADOR;

  @Column(name = "actuacion_id")
  private UUID actuacionId;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static CoactivaDocumentoDraft crear(
      UUID tenantId, UUID expedienteId, UUID plantillaId, String actuacionTipo, UUID createdBy) {
    CoactivaDocumentoDraft draft = new CoactivaDocumentoDraft();
    draft.id = UUID.randomUUID();
    draft.tenantId = tenantId;
    draft.expedienteId = expedienteId;
    draft.plantillaId = plantillaId;
    draft.actuacionTipo = actuacionTipo;
    draft.createdBy = createdBy;
    draft.status = BORRADOR;
    Instant now = Instant.now();
    draft.createdAt = now;
    draft.updatedAt = now;
    return draft;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public UUID getPlantillaId() {
    return plantillaId;
  }

  public String getActuacionTipo() {
    return actuacionTipo;
  }

  public String getPayload() {
    return payload;
  }

  public String getDatosExtraidos() {
    return datosExtraidos;
  }

  public String getOverrides() {
    return overrides;
  }

  public String getStoragePath() {
    return storagePath;
  }

  public int getVariablesPendientesCount() {
    return variablesPendientesCount;
  }

  public String getStatus() {
    return status;
  }

  public UUID getActuacionId() {
    return actuacionId;
  }

  public void guardar(
      String payload,
      String datosExtraidos,
      String overrides,
      String storagePath,
      int variablesPendientesCount) {
    this.payload = payload;
    this.datosExtraidos = datosExtraidos;
    this.overrides = overrides;
    this.storagePath = storagePath;
    this.variablesPendientesCount = variablesPendientesCount;
    this.status = BORRADOR;
    this.updatedAt = Instant.now();
  }

  public void marcarListo(UUID actuacionId) {
    this.actuacionId = actuacionId;
    this.status = LISTO;
    this.updatedAt = Instant.now();
  }
}
