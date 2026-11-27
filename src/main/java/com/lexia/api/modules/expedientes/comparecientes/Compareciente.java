package com.lexia.api.modules.expedientes.comparecientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Compareciente de un borrador o expediente (V123). */
@Entity
@Table(schema = "app", name = "expediente_compareciente")
public class Compareciente {

  public static final String ORIGEN_IA = "IA";
  public static final String ORIGEN_MANUAL = "MANUAL";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(nullable = false, length = 10)
  private String parte;

  @Column(nullable = false, length = 20)
  private String rol;

  @Column(nullable = false)
  private int orden;

  @Column(nullable = false, length = 240)
  private String nombres;

  @Column(length = 20)
  private String identificacion;

  @Column(name = "estado_civil", length = 40)
  private String estadoCivil;

  @Column(length = 120)
  private String profesion;

  @Column(name = "fecha_nacimiento")
  private LocalDate fechaNacimiento;

  /** null = el documento no lo dice. */
  private Boolean discapacidad;

  @Column(name = "persona_juridica", nullable = false)
  private boolean personaJuridica;

  @Column(name = "con_apoderado", nullable = false)
  private boolean conApoderado;

  @Column(nullable = false, length = 8)
  private String origen;

  @Column(name = "actualizado_por")
  private UUID actualizadoPor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Compareciente() {}

  public static Compareciente nuevo(UUID tenantId, UUID expedienteId, String origen, UUID usuario) {
    Compareciente c = new Compareciente();
    Instant ahora = Instant.now();
    c.id = UUID.randomUUID();
    c.tenantId = tenantId;
    c.expedienteId = expedienteId;
    c.origen = origen;
    c.actualizadoPor = usuario;
    c.createdAt = ahora;
    c.updatedAt = ahora;
    return c;
  }

  /** Datos ya validados por el servicio. */
  public void asignar(
      RolCompareciente.Parte parte,
      RolCompareciente rol,
      int orden,
      String nombres,
      String identificacion,
      String estadoCivil,
      String profesion,
      LocalDate fechaNacimiento,
      Boolean discapacidad,
      boolean personaJuridica,
      boolean conApoderado) {
    this.parte = parte.name();
    this.rol = rol.name();
    this.orden = orden;
    this.nombres = nombres;
    this.identificacion = identificacion;
    this.estadoCivil = estadoCivil;
    this.profesion = profesion;
    this.fechaNacimiento = fechaNacimiento;
    this.discapacidad = discapacidad;
    this.personaJuridica = personaJuridica;
    this.conApoderado = conApoderado;
    this.updatedAt = Instant.now();
  }

  /** Edición del abogado: desde ahora la IA no reemplaza la lista. */
  public void marcarManual(UUID usuario) {
    this.origen = ORIGEN_MANUAL;
    this.actualizadoPor = usuario;
    this.updatedAt = Instant.now();
  }

  /** Promoción del borrador. */
  public void moverA(UUID nuevoExpedienteId) {
    this.expedienteId = nuevoExpedienteId;
    this.updatedAt = Instant.now();
  }

  public RolCompareciente rolEnum() {
    return RolCompareciente.valueOf(rol);
  }

  public RolCompareciente.Parte parteEnum() {
    return RolCompareciente.Parte.valueOf(parte);
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

  public String getParte() {
    return parte;
  }

  public String getRol() {
    return rol;
  }

  public int getOrden() {
    return orden;
  }

  public String getNombres() {
    return nombres;
  }

  public String getIdentificacion() {
    return identificacion;
  }

  public String getEstadoCivil() {
    return estadoCivil;
  }

  public String getProfesion() {
    return profesion;
  }

  public LocalDate getFechaNacimiento() {
    return fechaNacimiento;
  }

  public Boolean getDiscapacidad() {
    return discapacidad;
  }

  public boolean isPersonaJuridica() {
    return personaJuridica;
  }

  public boolean isConApoderado() {
    return conApoderado;
  }

  public String getOrigen() {
    return origen;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
