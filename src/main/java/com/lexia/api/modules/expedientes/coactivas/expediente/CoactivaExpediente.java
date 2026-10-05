package com.lexia.api.modules.expedientes.coactivas.expediente;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "coactiva_expediente")
public class CoactivaExpediente implements Persistable<UUID> {

  public static final String RECIBIDO = "RECIBIDO";
  public static final String DIGITALIZADO = "DIGITALIZADO";
  public static final String EN_REVISION = "EN_REVISION";
  public static final String VERIFICADO = "VERIFICADO";
  public static final String ARCHIVADO = "ARCHIVADO";

  public static final String ANALISIS_NO_APLICA = "NO_APLICA";
  public static final String ANALISIS_ANALIZANDO = "ANALIZANDO";
  public static final String ANALISIS_ANALIZADO = "ANALIZADO";
  public static final String ANALISIS_ERROR = "ERROR_ANALISIS";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "nro_juicio", nullable = false, length = 40)
  private String nroJuicio;

  @Column(name = "nro_operacion", length = 40)
  private String nroOperacion;

  private Integer anio;

  @Column(name = "oficina_codigo", length = 32)
  private String oficinaCodigo;

  @Column(name = "delegado_id")
  private UUID delegadoId;

  @Column(name = "sae_user_id")
  private UUID saeUserId;

  @Column(name = "asistente_user_id")
  private UUID asistenteUserId;

  private Integer fojas;

  @Column(name = "estado_operativo", nullable = false, length = 16)
  private String estadoOperativo;

  @Column(name = "estado_analisis", nullable = false, length = 16)
  private String estadoAnalisis = ANALISIS_NO_APLICA;

  @Column(name = "analisis_archivo_id")
  private UUID analisisArchivoId;

  @Column(name = "etapa_reportada", length = 24)
  private String etapaReportada;

  @Column(name = "etapa_reportada_texto", length = 160)
  private String etapaReportadaTexto;

  @Column(name = "etapa_verificada", length = 24)
  private String etapaVerificada;

  @Column(name = "etapa_sugerida_ia", length = 24)
  private String etapaSugeridaIa;

  @Column(name = "etapa_confirmada_por")
  private UUID etapaConfirmadaPor;

  @Column(name = "etapa_confirmada_at")
  private Instant etapaConfirmadaAt;

  @Column(nullable = false, length = 8)
  private String semaforo;

  @Column(name = "semaforo_motivo", length = 400)
  private String semaforoMotivo;

  @Column(name = "fecha_citacion_opi")
  private LocalDate fechaCitacionOpi;

  @Column(name = "monto_original", precision = 14, scale = 2)
  private BigDecimal montoOriginal;

  @Column(name = "convenio_usado", nullable = false)
  private boolean convenioUsado;

  @Column(nullable = false)
  private boolean suspendido;

  @Column(name = "suspension_motivo", length = 400)
  private String suspensionMotivo;

  @Column(name = "acta_entrega_id")
  private UUID actaEntregaId;

  @Column(name = "fecha_ultima_actuacion")
  private LocalDate fechaUltimaActuacion;

  @Column(columnDefinition = "text")
  private String observaciones;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public static CoactivaExpediente create(UUID tenantId, UUID caseId, String nroJuicio, UUID userId) {
    CoactivaExpediente expediente = new CoactivaExpediente();
    Instant now = Instant.now();
    expediente.id = UUID.randomUUID();
    expediente.tenantId = tenantId;
    expediente.caseId = caseId;
    expediente.nroJuicio = nroJuicio;
    expediente.estadoOperativo = RECIBIDO;
    expediente.estadoAnalisis = ANALISIS_NO_APLICA;
    expediente.semaforo = "GRIS";
    expediente.createdAt = now;
    expediente.createdBy = userId;
    expediente.updatedAt = now;
    expediente.updatedBy = userId;
    return expediente;
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

  public void touch(UUID userId) {
    this.updatedAt = Instant.now();
    this.updatedBy = userId;
  }

  public void setDatosBase(String nroOperacion, Integer anio, String oficinaCodigo, Integer fojas) {
    this.nroOperacion = nroOperacion;
    this.anio = anio;
    this.oficinaCodigo = oficinaCodigo;
    this.fojas = fojas;
  }

  public void setEtapaReportada(String etapa, String texto) {
    this.etapaReportada = etapa;
    this.etapaReportadaTexto = texto;
  }

  public void confirmarEtapa(String etapa, UUID userId) {
    this.etapaVerificada = etapa;
    this.etapaConfirmadaPor = userId;
    this.etapaConfirmadaAt = Instant.now();
  }

  public void setSemaforo(String semaforo, String motivo) {
    this.semaforo = semaforo;
    this.semaforoMotivo = motivo;
  }

  public void softDelete(UUID userId) {
    this.deletedAt = Instant.now();
    touch(userId);
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public String getNroJuicio() {
    return nroJuicio;
  }

  public String getNroOperacion() {
    return nroOperacion;
  }

  public void setNroOperacion(String nroOperacion) {
    this.nroOperacion = nroOperacion;
  }

  public Integer getAnio() {
    return anio;
  }

  public void setAnio(Integer anio) {
    this.anio = anio;
  }

  public String getOficinaCodigo() {
    return oficinaCodigo;
  }

  public void setOficinaCodigo(String oficinaCodigo) {
    this.oficinaCodigo = oficinaCodigo;
  }

  public UUID getDelegadoId() {
    return delegadoId;
  }

  public void setDelegadoId(UUID delegadoId) {
    this.delegadoId = delegadoId;
  }

  public UUID getSaeUserId() {
    return saeUserId;
  }

  public void setSaeUserId(UUID saeUserId) {
    this.saeUserId = saeUserId;
  }

  public UUID getAsistenteUserId() {
    return asistenteUserId;
  }

  public void setAsistenteUserId(UUID asistenteUserId) {
    this.asistenteUserId = asistenteUserId;
  }

  public Integer getFojas() {
    return fojas;
  }

  public void setFojas(Integer fojas) {
    this.fojas = fojas;
  }

  public String getEstadoOperativo() {
    return estadoOperativo;
  }

  public void setEstadoOperativo(String estadoOperativo) {
    this.estadoOperativo = estadoOperativo;
  }

  public String getEstadoAnalisis() {
    return estadoAnalisis;
  }

  public UUID getAnalisisArchivoId() {
    return analisisArchivoId;
  }

  public void marcarAnalizando(UUID archivoId) {
    this.estadoAnalisis = ANALISIS_ANALIZANDO;
    this.analisisArchivoId = archivoId;
  }

  public void marcarAnalizado() {
    this.estadoAnalisis = ANALISIS_ANALIZADO;
  }

  public void marcarErrorAnalisis() {
    this.estadoAnalisis = ANALISIS_ERROR;
  }

  public String getEtapaReportada() {
    return etapaReportada;
  }

  public String getEtapaReportadaTexto() {
    return etapaReportadaTexto;
  }

  public String getEtapaVerificada() {
    return etapaVerificada;
  }

  public String getEtapaSugeridaIa() {
    return etapaSugeridaIa;
  }

  public void setEtapaSugeridaIa(String etapaSugeridaIa) {
    this.etapaSugeridaIa = etapaSugeridaIa;
  }

  public UUID getEtapaConfirmadaPor() {
    return etapaConfirmadaPor;
  }

  public Instant getEtapaConfirmadaAt() {
    return etapaConfirmadaAt;
  }

  public String getSemaforo() {
    return semaforo;
  }

  public String getSemaforoMotivo() {
    return semaforoMotivo;
  }

  public LocalDate getFechaCitacionOpi() {
    return fechaCitacionOpi;
  }

  public void setFechaCitacionOpi(LocalDate fechaCitacionOpi) {
    this.fechaCitacionOpi = fechaCitacionOpi;
  }

  public BigDecimal getMontoOriginal() {
    return montoOriginal;
  }

  public void setMontoOriginal(BigDecimal montoOriginal) {
    this.montoOriginal = montoOriginal;
  }

  public boolean isConvenioUsado() {
    return convenioUsado;
  }

  public void setConvenioUsado(boolean convenioUsado) {
    this.convenioUsado = convenioUsado;
  }

  public boolean isSuspendido() {
    return suspendido;
  }

  public String getSuspensionMotivo() {
    return suspensionMotivo;
  }

  public void setSuspension(boolean suspendido, String motivo) {
    this.suspendido = suspendido;
    this.suspensionMotivo = suspendido ? motivo : null;
  }

  public UUID getActaEntregaId() {
    return actaEntregaId;
  }

  public void setActaEntregaId(UUID actaEntregaId) {
    this.actaEntregaId = actaEntregaId;
  }

  public LocalDate getFechaUltimaActuacion() {
    return fechaUltimaActuacion;
  }

  public void setFechaUltimaActuacion(LocalDate fechaUltimaActuacion) {
    this.fechaUltimaActuacion = fechaUltimaActuacion;
  }

  public String getObservaciones() {
    return observaciones;
  }

  public void setObservaciones(String observaciones) {
    this.observaciones = observaciones;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Long getRowVersion() {
    return rowVersion;
  }
}
