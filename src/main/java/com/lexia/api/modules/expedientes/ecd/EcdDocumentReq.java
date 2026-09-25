package com.lexia.api.modules.expedientes.ecd;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "ecd_document_req")
public class EcdDocumentReq {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "document_type_code", nullable = false, length = 64)
  private String documentTypeCode;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  public String getDocumentTypeCode() {
    return documentTypeCode;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public static EcdDocumentReq create(UUID tenantId, String documentTypeCode, int sortOrder) {
    EcdDocumentReq row = new EcdDocumentReq();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.documentTypeCode = documentTypeCode;
    row.sortOrder = sortOrder;
    return row;
  }
}
