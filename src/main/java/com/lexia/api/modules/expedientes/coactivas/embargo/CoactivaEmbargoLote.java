package com.lexia.api.modules.expedientes.coactivas.embargo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Se inserta solo por {@link CoactivaEmbargoLoteRepository#abrir}; JPA únicamente lee y cierra. */
@Entity
@Table(schema = "app", name = "coactiva_embargo_lote")
public class CoactivaEmbargoLote {

  public static final String EN_PREPARACION = "EN_PREPARACION";
  public static final String ENTREGADO = "ENTREGADO";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  /** {@code coactiva_delegado.id}. Nulo solo en lotes históricos que no se pudieron inferir. */
  @Column(name = "delegado_id")
  private UUID delegadoId;

  @Column(name = "delegado_nombre", length = 200)
  private String delegadoNombre;

  @Column(nullable = false)
  private int numero;

  @Column(nullable = false, length = 24)
  private String estado;

  @Column(name = "fecha_corte", nullable = false)
  private Instant fechaCorte;

  @Column(name = "entregado_at")
  private Instant entregadoAt;

  @Column(name = "entregado_por")
  private UUID entregadoPor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public void entregar(UUID userId) {
    this.estado = ENTREGADO;
    this.entregadoAt = Instant.now();
    this.entregadoPor = userId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDelegadoId() {
    return delegadoId;
  }

  public String getDelegadoNombre() {
    return delegadoNombre;
  }

  public int getNumero() {
    return numero;
  }

  public String getEstado() {
    return estado;
  }

  public Instant getFechaCorte() {
    return fechaCorte;
  }

  public Instant getEntregadoAt() {
    return entregadoAt;
  }
}
