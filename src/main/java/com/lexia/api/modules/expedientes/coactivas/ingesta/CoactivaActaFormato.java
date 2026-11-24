package com.lexia.api.modules.expedientes.coactivas.ingesta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Formato de acta recordado: firma de la cabecera → qué columna es qué campo. */
@Entity
@Table(schema = "app", name = "coactiva_acta_formato")
public class CoactivaActaFormato {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 64)
  private String firma;

  @Column(length = 160)
  private String nombre;

  /** Celdas de la fila de cabecera tal como vinieron (para mostrarlas). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String cabecera;

  /** {@code {"<indice>": "<CAMPO>"}}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String mapeo;

  @Column(nullable = false)
  private int usos;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static CoactivaActaFormato create(UUID tenantId, String firma, UUID userId) {
    CoactivaActaFormato f = new CoactivaActaFormato();
    Instant now = Instant.now();
    f.id = UUID.randomUUID();
    f.tenantId = tenantId;
    f.firma = firma;
    f.createdBy = userId;
    f.createdAt = now;
    f.updatedAt = now;
    return f;
  }

  public void definir(String nombre, String cabecera, String mapeo) {
    this.nombre = nombre;
    this.cabecera = cabecera;
    this.mapeo = mapeo;
    this.updatedAt = Instant.now();
  }

  public void registrarUso() {
    this.usos++;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getFirma() {
    return firma;
  }

  public String getNombre() {
    return nombre;
  }

  public String getCabecera() {
    return cabecera;
  }

  public String getMapeo() {
    return mapeo;
  }

  public int getUsos() {
    return usos;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
