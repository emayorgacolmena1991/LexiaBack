package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "ejd_operation_document_req")
public class EjdOperationDocumentReq {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "operation_code", nullable = false, length = 64)
  private String operationCode;

  @Column(name = "document_type_code", nullable = false, length = 64)
  private String documentTypeCode;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "deprecated_at")
  private Instant deprecatedAt;

  public String getOperationCode() {
    return operationCode;
  }

  public Instant getDeprecatedAt() {
    return deprecatedAt;
  }

  public boolean isDeprecated() {
    return deprecatedAt != null;
  }

  public String getDocumentTypeCode() {
    return documentTypeCode;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public static EjdOperationDocumentReq create(
      UUID tenantId, String operationCode, String documentTypeCode, int sortOrder) {
    EjdOperationDocumentReq row = new EjdOperationDocumentReq();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.operationCode = operationCode;
    row.documentTypeCode = documentTypeCode;
    row.sortOrder = sortOrder;
    return row;
  }
}
