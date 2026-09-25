package com.lexia.api.modules.expedientes.documentos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "extracted_data")
public class ExtractedData {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "document_id")
  private UUID documentId;

  @Column(name = "field_label", nullable = false, length = 200)
  private String fieldLabel;

  @Column(name = "field_value")
  private String fieldValue;

  @Column(name = "field_group", length = 80)
  private String fieldGroup;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public String getFieldLabel() {
    return fieldLabel;
  }

  public String getFieldValue() {
    return fieldValue;
  }

  public String getFieldGroup() {
    return fieldGroup;
  }
}
