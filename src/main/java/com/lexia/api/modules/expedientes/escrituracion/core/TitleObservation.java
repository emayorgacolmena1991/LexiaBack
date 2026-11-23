package com.lexia.api.modules.expedientes.escrituracion.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "app", name = "title_observation")
public class TitleObservation {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "title_study_id", nullable = false)
  private UUID titleStudyId;

  @Column(nullable = false, columnDefinition = "text")
  private String detail;

  @Column(nullable = false, length = 32)
  private String status = "OPEN";

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Documento del expediente que origina la observación; null = general. */
  @Column(name = "legal_document_id")
  private UUID legalDocumentId;

  /** Otros documentos del expediente involucrados (ids ya validados). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "documentos_relacionados", columnDefinition = "jsonb")
  private List<String> documentosRelacionados;

  /** Código del catálogo cuando el problema es que falta un documento (sin legalDocumentId). */
  @Column(name = "tipo_documento_faltante", length = 64)
  private String tipoDocumentoFaltante;

  public static TitleObservation create(UUID tenantId, UUID titleStudyId, String detail) {
    return create(tenantId, titleStudyId, detail, null);
  }

  public static TitleObservation create(
      UUID tenantId, UUID titleStudyId, String detail, UUID legalDocumentId) {
    return create(tenantId, titleStudyId, detail, legalDocumentId, List.of(), null);
  }

  public static TitleObservation create(
      UUID tenantId,
      UUID titleStudyId,
      String detail,
      UUID legalDocumentId,
      List<UUID> documentosRelacionados,
      String tipoDocumentoFaltante) {
    TitleObservation obs = new TitleObservation();
    obs.documentosRelacionados =
        documentosRelacionados == null || documentosRelacionados.isEmpty()
            ? null
            : documentosRelacionados.stream().map(UUID::toString).toList();
    obs.tipoDocumentoFaltante = tipoDocumentoFaltante;
    obs.id = UUID.randomUUID();
    obs.tenantId = tenantId;
    obs.titleStudyId = titleStudyId;
    obs.detail = detail;
    obs.status = "OPEN";
    obs.createdAt = Instant.now();
    obs.legalDocumentId = legalDocumentId;
    return obs;
  }

  public UUID getLegalDocumentId() {
    return legalDocumentId;
  }

  public List<UUID> getDocumentosRelacionados() {
    return documentosRelacionados == null
        ? List.of()
        : documentosRelacionados.stream().map(UUID::fromString).toList();
  }

  public String getTipoDocumentoFaltante() {
    return tipoDocumentoFaltante;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTitleStudyId() {
    return titleStudyId;
  }

  public String getDetail() {
    return detail;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void resolve() {
    this.status = "RESOLVED";
  }
}
