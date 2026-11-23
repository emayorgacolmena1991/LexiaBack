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

/** Escritura generada por la notaría sobre una versión del envío. Nunca se actualiza. */
@Entity
@Immutable
@Table(schema = "control", name = "notary_escritura")
public class NotaryEscritura {

  @Id private UUID id;

  @Column(name = "remision_id", nullable = false)
  private UUID remisionId;

  @Column(name = "version_no", nullable = false)
  private int versionNo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "datos_formulario", nullable = false, columnDefinition = "jsonb")
  private String datosFormulario;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "campos_vacios", nullable = false, columnDefinition = "jsonb")
  private String camposVacios;

  @Column(name = "storage_ref", nullable = false, length = 1024)
  private String storageRef;

  @Column(name = "file_name", nullable = false, length = 500)
  private String fileName;

  @Column(name = "generated_by")
  private UUID generatedBy;

  @Column(name = "generated_at", nullable = false)
  private Instant generatedAt;

  protected NotaryEscritura() {}

  /** @param id generado por el llamador: nombra el archivo antes de insertar la fila */
  public static NotaryEscritura of(
      UUID id,
      UUID remisionId,
      int versionNo,
      String datosFormulario,
      String camposVacios,
      String storageRef,
      String fileName,
      UUID generatedBy) {
    NotaryEscritura e = new NotaryEscritura();
    e.id = id;
    e.remisionId = remisionId;
    e.versionNo = versionNo;
    e.datosFormulario = datosFormulario;
    e.camposVacios = camposVacios;
    e.storageRef = storageRef;
    e.fileName = fileName;
    e.generatedBy = generatedBy;
    e.generatedAt = Instant.now();
    return e;
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

  public String getDatosFormulario() {
    return datosFormulario;
  }

  public String getCamposVacios() {
    return camposVacios;
  }

  public String getStorageRef() {
    return storageRef;
  }

  public String getFileName() {
    return fileName;
  }

  public UUID getGeneratedBy() {
    return generatedBy;
  }

  public Instant getGeneratedAt() {
    return generatedAt;
  }
}
