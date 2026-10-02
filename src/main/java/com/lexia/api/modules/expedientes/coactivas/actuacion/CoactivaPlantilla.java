package com.lexia.api.modules.expedientes.coactivas.actuacion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "coactiva_plantilla")
public class CoactivaPlantilla {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 240)
  private String nombre;

  @Column(nullable = false, length = 24)
  private String etapa;

  @Column(nullable = false)
  private boolean activo;

  public UUID getId() {
    return id;
  }

  public String getNombre() {
    return nombre;
  }

  public String getEtapa() {
    return etapa;
  }

  public boolean isActivo() {
    return activo;
  }
}
