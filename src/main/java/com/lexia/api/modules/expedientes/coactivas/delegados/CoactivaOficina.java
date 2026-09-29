package com.lexia.api.modules.expedientes.coactivas.delegados;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "coactiva_oficina")
public class CoactivaOficina {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 32)
  private String codigo;

  @Column(nullable = false, length = 120)
  private String nombre;

  @Column(length = 80)
  private String provincia;

  @Column(nullable = false)
  private boolean activo;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaOficina create(
      UUID tenantId, String codigo, String nombre, String provincia, int sortOrder) {
    CoactivaOficina oficina = new CoactivaOficina();
    oficina.id = UUID.randomUUID();
    oficina.tenantId = tenantId;
    oficina.codigo = codigo;
    oficina.nombre = nombre;
    oficina.provincia = provincia;
    oficina.activo = true;
    oficina.sortOrder = sortOrder;
    oficina.createdAt = Instant.now();
    return oficina;
  }

  public UUID getId() {
    return id;
  }

  public String getCodigo() {
    return codigo;
  }

  public String getNombre() {
    return nombre;
  }

  public String getProvincia() {
    return provincia;
  }

  public boolean isActivo() {
    return activo;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void update(String nombre, String provincia, boolean activo, int sortOrder) {
    this.nombre = nombre;
    this.provincia = provincia;
    this.activo = activo;
    this.sortOrder = sortOrder;
  }
}
