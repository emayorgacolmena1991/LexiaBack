package com.lexia.api.modules.expedientes.coactivas.ingesta;

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
@Table(schema = "app", name = "coactiva_acta_item")
public class CoactivaActaItem implements Persistable<UUID> {

  public static final String PENDIENTE = "PENDIENTE";
  public static final String VINCULADO = "VINCULADO";
  public static final String DUPLICADO = "DUPLICADO";
  public static final String ERROR = "ERROR";
  public static final String OMITIDO = "OMITIDO";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "acta_id", nullable = false)
  private UUID actaId;

  @Column(nullable = false)
  private int fila;

  @Column(name = "oficina_codigo", length = 32)
  private String oficinaCodigo;

  @Column(name = "nro_operacion", length = 40)
  private String nroOperacion;

  @Column(name = "nro_juicio", length = 40)
  private String nroJuicio;

  private Integer anio;

  @Column(name = "deudor_nombre", length = 240)
  private String deudorNombre;

  @Column(name = "deudor_cedula", length = 20)
  private String deudorCedula;

  @Column(name = "etapa_reportada", length = 160)
  private String etapaReportada;

  private Integer fojas;

  @Column(name = "expediente_id")
  private UUID expedienteId;

  @Column(name = "estado_match", nullable = false, length = 16)
  private String estadoMatch;

  @Column(columnDefinition = "text")
  private String errores;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaActaItem create(UUID tenantId, UUID actaId, int fila) {
    CoactivaActaItem item = new CoactivaActaItem();
    item.id = UUID.randomUUID();
    item.tenantId = tenantId;
    item.actaId = actaId;
    item.fila = fila;
    item.estadoMatch = PENDIENTE;
    item.createdAt = Instant.now();
    return item;
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

  public void setDatos(
      String oficinaCodigo,
      String nroOperacion,
      String nroJuicio,
      Integer anio,
      String deudorNombre,
      String deudorCedula,
      String etapaReportada,
      Integer fojas) {
    this.oficinaCodigo = oficinaCodigo;
    this.nroOperacion = nroOperacion;
    this.nroJuicio = nroJuicio;
    this.anio = anio;
    this.deudorNombre = deudorNombre;
    this.deudorCedula = deudorCedula;
    this.etapaReportada = etapaReportada;
    this.fojas = fojas;
  }

  public void setResultado(String estadoMatch, UUID expedienteId, String errores) {
    this.estadoMatch = estadoMatch;
    this.expedienteId = expedienteId;
    this.errores = errores;
  }

  public UUID getActaId() {
    return actaId;
  }

  public int getFila() {
    return fila;
  }

  public String getOficinaCodigo() {
    return oficinaCodigo;
  }

  public String getNroOperacion() {
    return nroOperacion;
  }

  public String getNroJuicio() {
    return nroJuicio;
  }

  public Integer getAnio() {
    return anio;
  }

  public String getDeudorNombre() {
    return deudorNombre;
  }

  public String getDeudorCedula() {
    return deudorCedula;
  }

  public String getEtapaReportada() {
    return etapaReportada;
  }

  public Integer getFojas() {
    return fojas;
  }

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public String getEstadoMatch() {
    return estadoMatch;
  }

  public String getErrores() {
    return errores;
  }
}
