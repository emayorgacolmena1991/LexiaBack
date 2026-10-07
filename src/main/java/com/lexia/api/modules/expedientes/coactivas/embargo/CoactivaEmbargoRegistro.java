package com.lexia.api.modules.expedientes.coactivas.embargo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Solo lectura en JPA: inserción y edición pasan por {@link CoactivaEmbargoRegistroRepository#upsert}. */
@Entity
@Immutable
@Table(schema = "app", name = "coactiva_embargo_registro")
public class CoactivaEmbargoRegistro {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "lote_id", nullable = false)
  private UUID loteId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  private String juzgado;

  @Column(name = "oficina_origen_credito")
  private String oficinaOrigenCredito;

  private String operacion;

  @Column(name = "numero_juicio")
  private String numeroJuicio;

  @Column(name = "nombre_coactivado")
  private String nombreCoactivado;

  @Column(name = "nombre_titular_operacion")
  private String nombreTitularOperacion;

  @Column(name = "valor_transferido")
  private BigDecimal valorTransferido;

  @Column(name = "fecha_proceso")
  private LocalDate fechaProceso;

  @Column(name = "nombre_der_sac")
  private String nombreDerSac;

  @Column(name = "numero_oficio_respuesta")
  private String numeroOficioRespuesta;

  @Column(name = "numero_documento")
  private String numeroDocumento;

  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public UUID getId() {
    return id;
  }

  public UUID getLoteId() {
    return loteId;
  }

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public String getJuzgado() {
    return juzgado;
  }

  public String getOficinaOrigenCredito() {
    return oficinaOrigenCredito;
  }

  public String getOperacion() {
    return operacion;
  }

  public String getNumeroJuicio() {
    return numeroJuicio;
  }

  public String getNombreCoactivado() {
    return nombreCoactivado;
  }

  public String getNombreTitularOperacion() {
    return nombreTitularOperacion;
  }

  public BigDecimal getValorTransferido() {
    return valorTransferido;
  }

  public LocalDate getFechaProceso() {
    return fechaProceso;
  }

  public String getNombreDerSac() {
    return nombreDerSac;
  }

  public String getNumeroOficioRespuesta() {
    return numeroOficioRespuesta;
  }

  public String getNumeroDocumento() {
    return numeroDocumento;
  }

  public long getRowVersion() {
    return rowVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
