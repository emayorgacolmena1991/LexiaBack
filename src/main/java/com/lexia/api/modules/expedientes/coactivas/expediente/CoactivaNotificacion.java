package com.lexia.api.modules.expedientes.coactivas.expediente;

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
@Table(schema = "app", name = "coactiva_notificacion")
public class CoactivaNotificacion implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(name = "participante_id")
  private UUID participanteId;

  @Column(nullable = false, length = 16)
  private String acto;

  @Column(nullable = false, length = 16)
  private String medio;

  @Column(name = "numero_boleta")
  private Integer numeroBoleta;

  private LocalDate fecha;

  @Column(name = "archivo_id")
  private UUID archivoId;

  @Column(name = "pagina_desde")
  private Integer paginaDesde;

  @Column(name = "pagina_hasta")
  private Integer paginaHasta;

  @Column(nullable = false)
  private boolean valida;

  @Column(nullable = false, length = 16)
  private String fuente;

  @Column(length = 400)
  private String observacion;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public static CoactivaNotificacion create(
      UUID tenantId,
      UUID expedienteId,
      UUID participanteId,
      String acto,
      String medio,
      Integer numeroBoleta,
      LocalDate fecha,
      UUID archivoId,
      Integer paginaDesde,
      Integer paginaHasta,
      boolean valida,
      String fuente,
      String observacion,
      UUID createdBy) {
    CoactivaNotificacion n = new CoactivaNotificacion();
    n.id = UUID.randomUUID();
    n.tenantId = tenantId;
    n.expedienteId = expedienteId;
    n.participanteId = participanteId;
    n.acto = acto;
    n.medio = medio;
    n.numeroBoleta = numeroBoleta;
    n.fecha = fecha;
    n.archivoId = archivoId;
    n.paginaDesde = paginaDesde;
    n.paginaHasta = paginaHasta;
    n.valida = valida;
    n.fuente = fuente;
    n.observacion = observacion;
    n.createdAt = Instant.now();
    n.createdBy = createdBy;
    return n;
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

  public void softDelete() {
    this.deletedAt = Instant.now();
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public UUID getParticipanteId() {
    return participanteId;
  }

  public String getActo() {
    return acto;
  }

  public String getMedio() {
    return medio;
  }

  public Integer getNumeroBoleta() {
    return numeroBoleta;
  }

  public LocalDate getFecha() {
    return fecha;
  }

  public UUID getArchivoId() {
    return archivoId;
  }

  public Integer getPaginaDesde() {
    return paginaDesde;
  }

  public Integer getPaginaHasta() {
    return paginaHasta;
  }

  public boolean isValida() {
    return valida;
  }

  public String getFuente() {
    return fuente;
  }

  public String getObservacion() {
    return observacion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
