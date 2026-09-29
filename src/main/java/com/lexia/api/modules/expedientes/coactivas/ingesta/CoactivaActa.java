package com.lexia.api.modules.expedientes.coactivas.ingesta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "coactiva_acta")
public class CoactivaActa implements Persistable<UUID> {

  public static final String BORRADOR = "BORRADOR";
  public static final String CONFIRMADA = "CONFIRMADA";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 16)
  private String tipo;

  @Column(length = 240)
  private String titulo;

  @Column(name = "oficina_codigo", length = 32)
  private String oficinaCodigo;

  @Column(name = "fecha_acta")
  private LocalDate fechaActa;

  @Column(name = "fecha_recepcion")
  private LocalDate fechaRecepcion;

  @Column(name = "entregado_por", length = 200)
  private String entregadoPor;

  @Column(name = "recibido_por", length = 200)
  private String recibidoPor;

  @Column(name = "archivo_id")
  private UUID archivoId;

  @Column(name = "total_items", nullable = false)
  private int totalItems;

  @Column(nullable = false, length = 16)
  private String estado;

  @Column(nullable = false, length = 16)
  private String fuente;

  @Column(columnDefinition = "text")
  private String observaciones;

  @Column(name = "confirmada_at")
  private Instant confirmadaAt;

  @Column(name = "confirmada_por")
  private UUID confirmadaPor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public static CoactivaActa create(UUID tenantId, String tipo, String fuente, UUID userId) {
    CoactivaActa acta = new CoactivaActa();
    Instant now = Instant.now();
    acta.id = UUID.randomUUID();
    acta.tenantId = tenantId;
    acta.tipo = tipo;
    acta.fuente = fuente;
    acta.estado = BORRADOR;
    acta.createdAt = now;
    acta.createdBy = userId;
    acta.updatedAt = now;
    return acta;
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

  public void setCabecera(
      String titulo,
      String oficinaCodigo,
      LocalDate fechaActa,
      LocalDate fechaRecepcion,
      String entregadoPor,
      String recibidoPor,
      String observaciones) {
    this.titulo = titulo;
    this.oficinaCodigo = oficinaCodigo;
    this.fechaActa = fechaActa;
    this.fechaRecepcion = fechaRecepcion;
    this.entregadoPor = entregadoPor;
    this.recibidoPor = recibidoPor;
    this.observaciones = observaciones;
    this.updatedAt = Instant.now();
  }

  public void setArchivoId(UUID archivoId) {
    this.archivoId = archivoId;
  }

  public void setTotalItems(int totalItems) {
    this.totalItems = totalItems;
    this.updatedAt = Instant.now();
  }

  public void confirmar(UUID userId) {
    this.estado = CONFIRMADA;
    this.confirmadaAt = Instant.now();
    this.confirmadaPor = userId;
    this.updatedAt = this.confirmadaAt;
  }

  public void softDelete() {
    this.deletedAt = Instant.now();
  }

  public boolean isBorrador() {
    return BORRADOR.equals(estado);
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getTipo() {
    return tipo;
  }

  public String getTitulo() {
    return titulo;
  }

  public String getOficinaCodigo() {
    return oficinaCodigo;
  }

  public LocalDate getFechaActa() {
    return fechaActa;
  }

  public LocalDate getFechaRecepcion() {
    return fechaRecepcion;
  }

  public String getEntregadoPor() {
    return entregadoPor;
  }

  public String getRecibidoPor() {
    return recibidoPor;
  }

  public UUID getArchivoId() {
    return archivoId;
  }

  public int getTotalItems() {
    return totalItems;
  }

  public String getEstado() {
    return estado;
  }

  public String getFuente() {
    return fuente;
  }

  public String getObservaciones() {
    return observaciones;
  }

  public Instant getConfirmadaAt() {
    return confirmadaAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
