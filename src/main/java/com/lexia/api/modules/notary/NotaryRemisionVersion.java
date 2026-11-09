package com.lexia.api.modules.notary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Snapshot de un envío a notaría: JSON y copia propia de los .docx. Nunca se actualiza. */
@Entity
@Immutable
@Table(schema = "control", name = "notary_remision_version")
public class NotaryRemisionVersion {

  /** Copia de un .docx del borrador en el almacenamiento documental. */
  public record Archivo(UUID draftId, String storageRef, String fileName) {}

  @Id private UUID id;

  @Column(name = "remision_id", nullable = false)
  private UUID remisionId;

  @Column(name = "version_no", nullable = false)
  private int versionNo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "minuta_draft_id")
  private UUID minutaDraftId;

  @Column(name = "minuta_storage_ref", length = 1024)
  private String minutaStorageRef;

  @Column(name = "minuta_file_name", length = 500)
  private String minutaFileName;

  @Column(name = "contrato_draft_id")
  private UUID contratoDraftId;

  @Column(name = "contrato_storage_ref", length = 1024)
  private String contratoStorageRef;

  @Column(name = "contrato_file_name", length = 500)
  private String contratoFileName;

  @Column(name = "product_code", length = 64)
  private String productCode;

  @Column(name = "observaciones_bufete")
  private String observacionesBufete;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected NotaryRemisionVersion() {}

  /**
   * @param id generado por el llamador: nombra el archivo antes de insertar la fila
   * @param contrato {@code null} si el expediente no tiene contrato generado
   */
  public static NotaryRemisionVersion of(
      UUID id,
      UUID remisionId,
      int versionNo,
      String payload,
      Archivo minuta,
      Archivo contrato,
      String observacionesBufete,
      UUID createdBy,
      String productCode) {
    NotaryRemisionVersion v = new NotaryRemisionVersion();
    v.id = id;
    v.remisionId = remisionId;
    v.versionNo = versionNo;
    v.payload = payload;
    v.minutaDraftId = minuta.draftId();
    v.minutaStorageRef = minuta.storageRef();
    v.minutaFileName = minuta.fileName();
    if (contrato != null) {
      v.contratoDraftId = contrato.draftId();
      v.contratoStorageRef = contrato.storageRef();
      v.contratoFileName = contrato.fileName();
    }
    v.observacionesBufete = observacionesBufete;
    v.createdBy = createdBy;
    v.productCode = productCode;
    v.createdAt = Instant.now();
    return v;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRemisionId() {
    return remisionId;
  }

  public int getVersionNo() {
    return versionNo;
  }

  public String getPayload() {
    return payload;
  }

  public UUID getMinutaDraftId() {
    return minutaDraftId;
  }

  public String getMinutaStorageRef() {
    return minutaStorageRef;
  }

  public String getMinutaFileName() {
    return minutaFileName;
  }

  public UUID getContratoDraftId() {
    return contratoDraftId;
  }

  public String getContratoStorageRef() {
    return contratoStorageRef;
  }

  public String getContratoFileName() {
    return contratoFileName;
  }

  /** Producto del borrador al enviar: la notaría no puede leer las tablas del bufete. */
  public String getProductCode() {
    return productCode;
  }

  public String getObservacionesBufete() {
    return observacionesBufete;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
