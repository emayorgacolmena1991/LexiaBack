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
@Table(schema = "app", name = "coactiva_etapa_historial")
public class CoactivaEtapaHistorial implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(name = "etapa_anterior", length = 24)
  private String etapaAnterior;

  @Column(name = "etapa_nueva", nullable = false, length = 24)
  private String etapaNueva;

  @Column(length = 600)
  private String motivo;

  @Column(name = "usuario_id")
  private UUID usuarioId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaEtapaHistorial create(
      UUID tenantId, UUID expedienteId, String etapaAnterior, String etapaNueva, String motivo, UUID usuarioId) {
    CoactivaEtapaHistorial h = new CoactivaEtapaHistorial();
    h.id = UUID.randomUUID();
    h.tenantId = tenantId;
    h.expedienteId = expedienteId;
    h.etapaAnterior = etapaAnterior;
    h.etapaNueva = etapaNueva;
    h.motivo = motivo;
    h.usuarioId = usuarioId;
    h.createdAt = Instant.now();
    return h;
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

  public String getEtapaAnterior() {
    return etapaAnterior;
  }

  public String getEtapaNueva() {
    return etapaNueva;
  }

  public String getMotivo() {
    return motivo;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
