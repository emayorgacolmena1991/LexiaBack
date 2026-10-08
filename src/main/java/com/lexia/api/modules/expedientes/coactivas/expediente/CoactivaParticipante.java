package com.lexia.api.modules.expedientes.coactivas.expediente;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "coactiva_participante")
public class CoactivaParticipante implements Persistable<UUID> {

  public static final String DEUDOR = "DEUDOR";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(nullable = false, length = 16)
  private String rol;

  @Column(nullable = false)
  private int orden;

  @Column(name = "tipo_persona", nullable = false, length = 16)
  private String tipoPersona;

  @Column(name = "tipo_identificacion", nullable = false, length = 16)
  private String tipoIdentificacion;

  @Column(length = 20)
  private String identificacion;

  @Column(name = "nombre_completo", nullable = false, length = 240)
  private String nombreCompleto;

  @Column(columnDefinition = "text")
  private String emails;

  @Column(length = 400)
  private String direccion;

  @Column(length = 40)
  private String telefono;

  @Column(nullable = false, length = 16)
  private String fuente;

  @Column(name = "confianza_ia", precision = 5, scale = 2)
  private BigDecimal confianzaIa;

  @Column(nullable = false)
  private boolean verificado;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public static CoactivaParticipante create(
      UUID tenantId, UUID expedienteId, String rol, int orden, String nombreCompleto, String fuente) {
    CoactivaParticipante participante = new CoactivaParticipante();
    Instant now = Instant.now();
    participante.id = UUID.randomUUID();
    participante.tenantId = tenantId;
    participante.expedienteId = expedienteId;
    participante.rol = rol;
    participante.orden = orden;
    participante.nombreCompleto = nombreCompleto;
    participante.fuente = fuente;
    participante.tipoPersona = "NATURAL";
    participante.tipoIdentificacion = "CEDULA";
    participante.createdAt = now;
    participante.updatedAt = now;
    return participante;
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

  public void update(
      String rol,
      int orden,
      String tipoPersona,
      String tipoIdentificacion,
      String identificacion,
      String nombreCompleto,
      String emails,
      String direccion,
      String telefono,
      boolean verificado) {
    this.rol = rol;
    this.orden = orden;
    this.tipoPersona = tipoPersona;
    this.tipoIdentificacion = tipoIdentificacion;
    this.identificacion = identificacion;
    this.nombreCompleto = nombreCompleto;
    this.emails = emails;
    this.direccion = direccion;
    this.telefono = telefono;
    this.verificado = verificado;
    this.updatedAt = Instant.now();
  }

  public void setIdentificacion(String identificacion, String tipoIdentificacion) {
    this.identificacion = identificacion;
    this.tipoIdentificacion = tipoIdentificacion;
    this.updatedAt = Instant.now();
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

  public String getRol() {
    return rol;
  }

  public int getOrden() {
    return orden;
  }

  public String getTipoPersona() {
    return tipoPersona;
  }

  public String getTipoIdentificacion() {
    return tipoIdentificacion;
  }

  public String getIdentificacion() {
    return identificacion;
  }

  public String getNombreCompleto() {
    return nombreCompleto;
  }

  public String getEmails() {
    return emails;
  }

  public String getDireccion() {
    return direccion;
  }

  public String getTelefono() {
    return telefono;
  }

  public String getFuente() {
    return fuente;
  }

  public BigDecimal getConfianzaIa() {
    return confianzaIa;
  }

  public boolean isVerificado() {
    return verificado;
  }
}
