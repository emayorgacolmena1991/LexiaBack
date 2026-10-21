package com.lexia.api.modules.expedientes.coactivas.actuacion;

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

@Entity
@Table(schema = "app", name = "coactiva_actuacion")
public class CoactivaActuacion implements Persistable<UUID> {

  public static final String GENERADO = "GENERADO";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(name = "plantilla_id", nullable = false)
  private UUID plantillaId;

  @Column(name = "archivo_id")
  private UUID archivoId;

  @Column(nullable = false, length = 24)
  private String estado;

  @Column(name = "idempotency_key", length = 120)
  private String idempotencyKey;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaActuacion crear(
      UUID tenantId,
      UUID expedienteId,
      UUID plantillaId,
      UUID archivoId,
      String idempotencyKey,
      UUID createdBy) {
    CoactivaActuacion actuacion = new CoactivaActuacion();
    actuacion.id = UUID.randomUUID();
    actuacion.tenantId = tenantId;
    actuacion.expedienteId = expedienteId;
    actuacion.plantillaId = plantillaId;
    actuacion.archivoId = archivoId;
    actuacion.estado = GENERADO;
    actuacion.idempotencyKey = idempotencyKey;
    actuacion.createdBy = createdBy;
    actuacion.createdAt = Instant.now();
    return actuacion;
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

  public UUID getPlantillaId() {
    return plantillaId;
  }

  public UUID getArchivoId() {
    return archivoId;
  }

  public String getEstado() {
    return estado;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
