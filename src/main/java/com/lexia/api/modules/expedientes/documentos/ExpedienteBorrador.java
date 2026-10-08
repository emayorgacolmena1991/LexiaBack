package com.lexia.api.modules.expedientes.documentos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "app", name = "expediente_borrador")
public class ExpedienteBorrador {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "id_acto", length = 64)
  private String idActo;

  @Column(name = "product_code", length = 64)
  private String productCode;

  @Column(length = 64)
  private String canton;

  @Column(name = "ingestion_mode", nullable = false, length = 32)
  private String ingestionMode;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Estudio IA (documentos, datos consolidados, dictamen) mientras no hay expediente formal. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "datos_extraidos", columnDefinition = "jsonb")
  private String datosExtraidos;

  @Column(name = "promoted_case_id")
  private UUID promotedCaseId;

  public static ExpedienteBorrador loaded(
      UUID id,
      UUID tenantId,
      String idActo,
      String productCode,
      String canton,
      String ingestionMode) {
    ExpedienteBorrador row = new ExpedienteBorrador();
    row.id = id;
    row.tenantId = tenantId;
    row.idActo = idActo;
    row.productCode = productCode;
    row.canton = canton;
    row.ingestionMode = ingestionMode;
    row.status = "BORRADOR";
    row.createdAt = Instant.now();
    return row;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getIdActo() {
    return idActo;
  }

  public String getProductCode() {
    return productCode;
  }

  public String getCanton() {
    return canton;
  }

  public String getIngestionMode() {
    return ingestionMode;
  }

  public String getDatosExtraidos() {
    return datosExtraidos;
  }

  public void setDatosExtraidos(String datosExtraidos) {
    this.datosExtraidos = datosExtraidos;
  }

  public UUID getPromotedCaseId() {
    return promotedCaseId;
  }

  public void setPromotedCaseId(UUID promotedCaseId) {
    this.promotedCaseId = promotedCaseId;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
