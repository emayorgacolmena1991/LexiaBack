package com.lexia.api.modules.expedientes.requisitos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Condición del caso de un borrador o expediente ({@code expediente_id} es el id de cualquiera de
 * los dos). La fase 3 la propone (IA) o la agrega el abogado (MANUAL); solo cuenta si está
 * confirmada y no descartada.
 */
@Entity
@Table(schema = "app", name = "expediente_condicion")
public class ExpedienteCondicion {

  public static final String ORIGEN_IA = "IA";
  public static final String ORIGEN_MANUAL = "MANUAL";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(nullable = false, length = 40)
  private String condicion;

  @Column(nullable = false, length = 8)
  private String origen;

  @Column(nullable = false)
  private boolean confirmada;

  @Column(nullable = false)
  private boolean descartada;

  @Column(length = 500)
  private String evidencia;

  @Column(name = "documento_id")
  private UUID documentoId;

  @Column(precision = 4, scale = 3)
  private BigDecimal confianza;

  @Column(name = "actualizado_por")
  private UUID actualizadoPor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ExpedienteCondicion() {}

  public static ExpedienteCondicion nueva(
      UUID tenantId, UUID expedienteId, CondicionCaso condicion, String origen, UUID usuario) {
    ExpedienteCondicion c = new ExpedienteCondicion();
    Instant ahora = Instant.now();
    c.id = UUID.randomUUID();
    c.tenantId = tenantId;
    c.expedienteId = expedienteId;
    c.condicion = condicion.name();
    c.origen = origen;
    c.actualizadoPor = usuario;
    c.createdAt = ahora;
    c.updatedAt = ahora;
    return c;
  }

  /** Cuenta para los requisitos. */
  public boolean vigente() {
    return confirmada && !descartada;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public String getCondicion() {
    return condicion;
  }

  public String getOrigen() {
    return origen;
  }

  public boolean isConfirmada() {
    return confirmada;
  }

  public boolean isDescartada() {
    return descartada;
  }

  public String getEvidencia() {
    return evidencia;
  }

  public UUID getDocumentoId() {
    return documentoId;
  }

  public BigDecimal getConfianza() {
    return confianza;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
