package com.lexia.api.modules.expedientes.coactivas.ia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Catálogo de system prompts por tipo de documento y etapa coactiva. {@code '*'} actúa como
 * comodín. Global (sin tenant): las reglas de BanEcuador cambian por BD, no por despliegue.
 */
@Entity
@Table(schema = "app", name = "coactiva_prompt_catalog")
public class CoactivaPromptCatalog {

  public static final String COMODIN = "*";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tipo_documento", nullable = false, length = 32)
  private String tipoDocumento;

  @Column(nullable = false, length = 32)
  private String etapa;

  @Column(name = "system_prompt", nullable = false)
  private String systemPrompt;

  /** Vacío en prompts de diagnóstico. Ruta classpath cuando {@code tipo_documento} es PLANTILLA. */
  @Column(name = "plantilla_archivo", nullable = false, length = 255)
  private String plantillaArchivo = "";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "variables_requeridas", columnDefinition = "jsonb")
  private String variablesRequeridas;

  @Column(length = 255)
  private String descripcion;

  @Column(nullable = false)
  private boolean activo = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  public Long getId() {
    return id;
  }

  public String getTipoDocumento() {
    return tipoDocumento;
  }

  public String getEtapa() {
    return etapa;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public String getPlantillaArchivo() {
    return plantillaArchivo;
  }

  public String getVariablesRequeridas() {
    return variablesRequeridas;
  }

  public String getDescripcion() {
    return descripcion;
  }

  public boolean isActivo() {
    return activo;
  }
}
