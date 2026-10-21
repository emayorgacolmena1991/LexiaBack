package com.lexia.api.modules.expedientes.coactivas.actuacion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "coactiva_medida")
public class CoactivaMedida implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(nullable = false, length = 40)
  private String tipo;

  @Column(columnDefinition = "text")
  private String descripcion;

  private LocalDate fecha;

  @Column(nullable = false, length = 24)
  private String estado;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaMedida crear(
      UUID tenantId, UUID expedienteId, String tipo, String descripcion, LocalDate fecha, UUID createdBy) {
    CoactivaMedida medida = new CoactivaMedida();
    medida.id = UUID.randomUUID();
    medida.tenantId = tenantId;
    medida.expedienteId = expedienteId;
    medida.tipo = tipo;
    medida.descripcion = descripcion;
    medida.fecha = fecha;
    medida.estado = "VIGENTE";
    medida.createdBy = createdBy;
    medida.createdAt = Instant.now();
    return medida;
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

  public String getTipo() {
    return tipo;
  }

  public String getDescripcion() {
    return descripcion;
  }

  public LocalDate getFecha() {
    return fecha;
  }

  public String getEstado() {
    return estado;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
