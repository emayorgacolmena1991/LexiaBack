package com.lexia.api.modules.expedientes.coactivas.expediente;

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
@Table(schema = "app", name = "coactiva_evento")
public class CoactivaEvento implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(nullable = false, length = 40)
  private String tipo;

  @Column(nullable = false, length = 240)
  private String titulo;

  @Column(columnDefinition = "text")
  private String detalle;

  @Column(name = "usuario_id")
  private UUID usuarioId;

  @Column(name = "archivo_id")
  private UUID archivoId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaEvento create(
      UUID tenantId, UUID expedienteId, String tipo, String titulo, String detalle, UUID usuarioId) {
    return create(tenantId, expedienteId, tipo, titulo, detalle, usuarioId, null);
  }

  public static CoactivaEvento create(
      UUID tenantId,
      UUID expedienteId,
      String tipo,
      String titulo,
      String detalle,
      UUID usuarioId,
      UUID archivoId) {
    CoactivaEvento e = new CoactivaEvento();
    e.id = UUID.randomUUID();
    e.tenantId = tenantId;
    e.expedienteId = expedienteId;
    e.tipo = tipo;
    e.titulo = titulo.length() > 240 ? titulo.substring(0, 240) : titulo;
    e.detalle = detalle;
    e.usuarioId = usuarioId;
    e.archivoId = archivoId;
    e.createdAt = Instant.now();
    return e;
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

  public String getTipo() {
    return tipo;
  }

  public String getTitulo() {
    return titulo;
  }

  public String getDetalle() {
    return detalle;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public UUID getArchivoId() {
    return archivoId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
