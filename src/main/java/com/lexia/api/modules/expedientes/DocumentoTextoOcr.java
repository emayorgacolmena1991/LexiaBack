package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "documento_texto_ocr")
public class DocumentoTextoOcr {

  @Id private UUID id;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "id_expediente", nullable = false, length = 64)
  private String idExpediente;

  @Column(name = "id_documento", nullable = false, length = 64)
  private String idDocumento;

  @Column(name = "tipo_documento", length = 64)
  private String tipoDocumento;

  @Column(name = "texto_ocr", columnDefinition = "TEXT")
  private String textoOcr;

  @Column(name = "analisis_json", columnDefinition = "TEXT")
  private String analisisJson;

  @Column(name = "estado_doc", nullable = false, length = 32)
  private String estadoDoc;

  @Column(columnDefinition = "TEXT")
  private String motivo;

  private Integer confianza;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
    if (estadoDoc == null) {
      estadoDoc = "EN_PROCESO";
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public String getIdExpediente() {
    return idExpediente;
  }

  public void setIdExpediente(String idExpediente) {
    this.idExpediente = idExpediente;
  }

  public String getIdDocumento() {
    return idDocumento;
  }

  public void setIdDocumento(String idDocumento) {
    this.idDocumento = idDocumento;
  }

  public String getTipoDocumento() {
    return tipoDocumento;
  }

  public void setTipoDocumento(String tipoDocumento) {
    this.tipoDocumento = tipoDocumento;
  }

  public String getTextoOcr() {
    return textoOcr;
  }

  public void setTextoOcr(String textoOcr) {
    this.textoOcr = textoOcr;
  }

  public String getAnalisisJson() {
    return analisisJson;
  }

  public void setAnalisisJson(String analisisJson) {
    this.analisisJson = analisisJson;
  }

  public String getEstadoDoc() {
    return estadoDoc;
  }

  public void setEstadoDoc(String estadoDoc) {
    this.estadoDoc = estadoDoc;
  }

  public String getMotivo() {
    return motivo;
  }

  public void setMotivo(String motivo) {
    this.motivo = motivo;
  }

  public Integer getConfianza() {
    return confianza;
  }

  public void setConfianza(Integer confianza) {
    this.confianza = confianza;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
