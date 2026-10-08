package com.lexia.api.modules.expedientes.coactivas.delegados;

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
@Table(schema = "app", name = "coactiva_delegado")
public class CoactivaDelegado implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 200)
  private String nombre;

  @Column(length = 20)
  private String identificacion;

  @Column(length = 160)
  private String cargo;

  @Column(name = "resolucion_numero", length = 120)
  private String resolucionNumero;

  @Column(name = "resolucion_fecha")
  private LocalDate resolucionFecha;

  @Column(name = "vigente_desde")
  private LocalDate vigenteDesde;

  @Column(name = "vigente_hasta")
  private LocalDate vigenteHasta;

  @Column(length = 200)
  private String email;

  @Column(nullable = false)
  private boolean activo;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public static CoactivaDelegado create(UUID tenantId, String nombre, UUID userId) {
    CoactivaDelegado delegado = new CoactivaDelegado();
    delegado.id = UUID.randomUUID();
    delegado.tenantId = tenantId;
    delegado.nombre = nombre;
    delegado.activo = true;
    Instant now = Instant.now();
    delegado.createdAt = now;
    delegado.updatedAt = now;
    delegado.createdBy = userId;
    delegado.updatedBy = userId;
    return delegado;
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

  public boolean vigenteEn(LocalDate fecha) {
    if (!activo || deletedAt != null) {
      return false;
    }
    if (vigenteDesde != null && fecha.isBefore(vigenteDesde)) {
      return false;
    }
    return vigenteHasta == null || !fecha.isAfter(vigenteHasta);
  }

  public void update(
      String nombre,
      String identificacion,
      String cargo,
      String resolucionNumero,
      LocalDate resolucionFecha,
      LocalDate vigenteDesde,
      LocalDate vigenteHasta,
      String email,
      boolean activo,
      UUID userId) {
    this.nombre = nombre;
    this.identificacion = identificacion;
    this.cargo = cargo;
    this.resolucionNumero = resolucionNumero;
    this.resolucionFecha = resolucionFecha;
    this.vigenteDesde = vigenteDesde;
    this.vigenteHasta = vigenteHasta;
    this.email = email;
    this.activo = activo;
    this.updatedBy = userId;
    this.updatedAt = Instant.now();
  }

  public void softDelete(UUID userId) {
    this.deletedAt = Instant.now();
    this.activo = false;
    this.updatedBy = userId;
    this.updatedAt = this.deletedAt;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getNombre() {
    return nombre;
  }

  public String getIdentificacion() {
    return identificacion;
  }

  public String getCargo() {
    return cargo;
  }

  public String getResolucionNumero() {
    return resolucionNumero;
  }

  public LocalDate getResolucionFecha() {
    return resolucionFecha;
  }

  public LocalDate getVigenteDesde() {
    return vigenteDesde;
  }

  public LocalDate getVigenteHasta() {
    return vigenteHasta;
  }

  public String getEmail() {
    return email;
  }

  public boolean isActivo() {
    return activo;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }
}
