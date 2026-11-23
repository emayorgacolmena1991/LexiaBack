package com.lexia.api.modules.expedientes.coactivas.expediente;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "coactiva_tarea_sugerida")
public class CoactivaTareaSugerida implements Persistable<UUID> {

  public static final String SUGERIDA = "SUGERIDA";
  public static final String INCORPORADA = "INCORPORADA";
  public static final String DESCARTADA = "DESCARTADA";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(name = "analisis_id")
  private UUID analisisId;

  @Column(nullable = false, length = 240)
  private String titulo;

  @Column(columnDefinition = "text")
  private String descripcion;

  @Column(nullable = false, length = 16)
  private String prioridad;

  @Column(nullable = false, length = 16)
  private String estado;

  @Column(name = "origen_ia", nullable = false)
  private boolean origenIa;

  @Column(name = "creado_en", nullable = false)
  private Instant creadoEn;

  public static CoactivaTareaSugerida nueva(
      UUID tenantId,
      UUID expedienteId,
      UUID analisisId,
      String titulo,
      String descripcion,
      String prioridad) {
    CoactivaTareaSugerida row = new CoactivaTareaSugerida();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.expedienteId = expedienteId;
    row.analisisId = analisisId;
    row.titulo = titulo == null || titulo.isBlank() ? "Tarea sugerida" : titulo.trim();
    if (row.titulo.length() > 240) {
      row.titulo = row.titulo.substring(0, 240);
    }
    row.descripcion = descripcion;
    row.prioridad = prioridadValida(prioridad);
    row.estado = SUGERIDA;
    row.origenIa = true;
    row.creadoEn = Instant.now();
    return row;
  }

  public void incorporar() {
    this.estado = INCORPORADA;
  }

  public void descartar() {
    this.estado = DESCARTADA;
  }

  private static String prioridadValida(String prioridad) {
    if (prioridad == null) {
      return "MEDIA";
    }
    String valor = prioridad.trim().toUpperCase();
    return "ALTA".equals(valor) || "MEDIA".equals(valor) || "BAJA".equals(valor) ? valor : "MEDIA";
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

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public UUID getAnalisisId() {
    return analisisId;
  }

  public String getTitulo() {
    return titulo;
  }

  public String getDescripcion() {
    return descripcion;
  }

  public String getPrioridad() {
    return prioridad;
  }

  public String getEstado() {
    return estado;
  }

  public boolean isOrigenIa() {
    return origenIa;
  }

  public Instant getCreadoEn() {
    return creadoEn;
  }
}
