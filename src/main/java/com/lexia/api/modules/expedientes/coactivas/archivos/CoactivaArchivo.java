package com.lexia.api.modules.expedientes.coactivas.archivos;

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
@Table(schema = "app", name = "coactiva_archivo")
public class CoactivaArchivo implements Persistable<UUID> {

  public static final String EXPEDIENTE_ESCANEADO = "EXPEDIENTE_ESCANEADO";
  public static final String ACTA = "ACTA";
  public static final String VINCULADO = "VINCULADO";
  public static final String SIN_ASIGNAR = "SIN_ASIGNAR";

  /** Estados de la validación documental por IA. */
  public static final String IA_NO_APLICA = "NO_APLICA";
  public static final String IA_ANALIZANDO = "ANALIZANDO";
  public static final String IA_APROBADO = "APROBADO";
  public static final String IA_RECHAZADO = "RECHAZADO";
  public static final String IA_ERROR = "ERROR";
  public static final String IA_APROBADO_MANUAL = "APROBADO_MANUAL";

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id")
  private UUID expedienteId;

  @Column(name = "acta_id")
  private UUID actaId;

  @Column(nullable = false, length = 32)
  private String tipo;

  @Column(name = "nombre_original", nullable = false, length = 400)
  private String nombreOriginal;

  @Column(name = "mime_type", length = 128)
  private String mimeType;

  @Column(name = "tamano_bytes", nullable = false)
  private long tamanoBytes;

  @Column(length = 64)
  private String sha256;

  @Column(name = "storage_path", nullable = false, length = 1024)
  private String storagePath;

  @Column(name = "nro_juicio_detectado", length = 40)
  private String nroJuicioDetectado;

  @Column(name = "estado_vinculo", nullable = false, length = 16)
  private String estadoVinculo;

  @Column(name = "subido_por")
  private UUID subidoPor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "estado_ia", nullable = false, length = 16)
  private String estadoIa = IA_NO_APLICA;

  @Column(name = "motivo_rechazo_ia")
  private String motivoRechazoIa;

  @Column(name = "confianza_ia")
  private Integer confianzaIa;

  @Column(name = "checklist_ia")
  private String checklistIa;

  @Column(name = "etapa_ia", length = 32)
  private String etapaIa;

  @Column(name = "ia_analizado_at")
  private Instant iaAnalizadoAt;

  @Column(name = "override_por")
  private UUID overridePor;

  @Column(name = "override_at")
  private Instant overrideAt;

  @Column(name = "override_motivo", length = 600)
  private String overrideMotivo;

  public static CoactivaArchivo create(
      UUID id,
      UUID tenantId,
      String tipo,
      String nombreOriginal,
      String mimeType,
      long tamanoBytes,
      String sha256,
      String storagePath,
      UUID subidoPor) {
    CoactivaArchivo archivo = new CoactivaArchivo();
    archivo.id = id;
    archivo.tenantId = tenantId;
    archivo.tipo = tipo;
    archivo.nombreOriginal = nombreOriginal;
    archivo.mimeType = mimeType;
    archivo.tamanoBytes = tamanoBytes;
    archivo.sha256 = sha256;
    archivo.storagePath = storagePath;
    archivo.subidoPor = subidoPor;
    archivo.estadoVinculo = SIN_ASIGNAR;
    archivo.createdAt = Instant.now();
    return archivo;
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

  public void vincularExpediente(UUID expedienteId) {
    this.expedienteId = expedienteId;
    this.estadoVinculo = VINCULADO;
  }

  public void vincularActa(UUID actaId) {
    this.actaId = actaId;
    this.estadoVinculo = VINCULADO;
  }

  public void setNroJuicioDetectado(String nroJuicioDetectado) {
    this.nroJuicioDetectado = nroJuicioDetectado;
  }

  public void softDelete() {
    this.deletedAt = Instant.now();
  }

  // --- Validación IA ---------------------------------------------------------

  /** Encola el archivo para análisis: limpia el resultado anterior (no toca el override). */
  public void iniciarAnalisisIa(String etapa) {
    this.estadoIa = IA_ANALIZANDO;
    this.etapaIa = etapa;
    this.motivoRechazoIa = null;
    this.confianzaIa = null;
    this.checklistIa = null;
    this.iaAnalizadoAt = null;
  }

  public void registrarResultadoIa(
      String estado, String motivoRechazo, Integer confianza, String checklistJson) {
    this.estadoIa = estado;
    this.motivoRechazoIa = motivoRechazo;
    this.confianzaIa = confianza;
    this.checklistIa = checklistJson;
    this.iaAnalizadoAt = Instant.now();
  }

  public void registrarErrorIa(String motivo) {
    registrarResultadoIa(IA_ERROR, motivo, null, null);
  }

  /** Override humano: el abogado/supervisor fuerza la aprobación. Conserva el motivo de la IA. */
  public void aprobarManualmente(UUID usuarioId, String motivo) {
    this.estadoIa = IA_APROBADO_MANUAL;
    this.overridePor = usuarioId;
    this.overrideAt = Instant.now();
    this.overrideMotivo = motivo;
  }

  public boolean analizando() {
    return IA_ANALIZANDO.equals(estadoIa);
  }

  public boolean validoParaChecklist() {
    return IA_NO_APLICA.equals(estadoIa)
        || IA_APROBADO.equals(estadoIa)
        || IA_APROBADO_MANUAL.equals(estadoIa);
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getExpedienteId() {
    return expedienteId;
  }

  public UUID getActaId() {
    return actaId;
  }

  public String getTipo() {
    return tipo;
  }

  public String getNombreOriginal() {
    return nombreOriginal;
  }

  public String getMimeType() {
    return mimeType;
  }

  public long getTamanoBytes() {
    return tamanoBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public String getStoragePath() {
    return storagePath;
  }

  public String getNroJuicioDetectado() {
    return nroJuicioDetectado;
  }

  public String getEstadoVinculo() {
    return estadoVinculo;
  }

  public UUID getSubidoPor() {
    return subidoPor;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getEstadoIa() {
    return estadoIa;
  }

  public String getMotivoRechazoIa() {
    return motivoRechazoIa;
  }

  public Integer getConfianzaIa() {
    return confianzaIa;
  }

  public String getChecklistIa() {
    return checklistIa;
  }

  public String getEtapaIa() {
    return etapaIa;
  }

  public Instant getIaAnalizadoAt() {
    return iaAnalizadoAt;
  }

  public UUID getOverridePor() {
    return overridePor;
  }

  public Instant getOverrideAt() {
    return overrideAt;
  }

  public String getOverrideMotivo() {
    return overrideMotivo;
  }
}
