package com.lexia.api.modules.expedientes.coactivas.ia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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

  public String getDescripcion() {
    return descripcion;
  }

  public boolean isActivo() {
    return activo;
  }
}
