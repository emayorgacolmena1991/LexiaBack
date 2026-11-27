package com.lexia.api.modules.expedientes.documentos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "document_requirement")
public class DocumentRequirement {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "product_code", nullable = false, length = 64)
  private String productCode;

  @Column(name = "document_type_code", nullable = false, length = 64)
  private String documentTypeCode;

  @Column(name = "is_mandatory", nullable = false)
  private boolean mandatory = true;

  @Column(name = "max_validity_days", nullable = false)
  private int maxValidityDays = 60;

  @Column(nullable = false, length = 64)
  private String canton = "ALL";

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Código de {@code CondicionCaso}; null = aplica siempre (V122). */
  @Column(length = 40)
  private String condicion;

  /** Se pide uno por compareciente del rol indicado. */
  @Column(name = "por_persona", nullable = false)
  private boolean porPersona;

  /** COMPRADOR, VENDEDOR, AMBOS, REPRESENTANTE o null. */
  @Column(name = "rol_persona", length = 16)
  private String rolPersona;

  /** Ausente, vencido o con datos que no coinciden bloquea el avance (EJD.V1). */
  @Column(nullable = false)
  private boolean esencial;

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getProductCode() {
    return productCode;
  }

  public String getDocumentTypeCode() {
    return documentTypeCode;
  }

  public boolean isMandatory() {
    return mandatory;
  }

  public int getMaxValidityDays() {
    return maxValidityDays;
  }

  public String getCanton() {
    return canton;
  }

  public String getDescription() {
    return description;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public String getCondicion() {
    return condicion;
  }

  public boolean isPorPersona() {
    return porPersona;
  }

  public String getRolPersona() {
    return rolPersona;
  }

  public boolean isEsencial() {
    return esencial;
  }
}
