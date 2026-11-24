package com.lexia.api.modules.expedientes.caso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Etapa del catálogo de estatus del proceso (V117). Se edita en la tabla, no en el código. */
@Entity
@Table(schema = "app", name = "estatus_etapa")
public class EstatusEtapa {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 60)
  private String codigo;

  @Column(nullable = false, length = 120)
  private String nombre;

  /** Texto de la columna «ETAPA» de la matriz de la entidad; {@code null} = el nombre. */
  @Column(name = "etiqueta_matriz", length = 120)
  private String etiquetaMatriz;

  @Column(nullable = false)
  private int orden;

  @Column(name = "texto_sugerido", nullable = false)
  private String textoSugerido;

  @Column(nullable = false)
  private boolean activo;

  protected EstatusEtapa() {}

  /** Para tests: las etapas reales vienen de la semilla de V117. */
  public static EstatusEtapa of(
      UUID tenantId, String codigo, String nombre, String etiquetaMatriz, int orden, String textoSugerido) {
    EstatusEtapa e = new EstatusEtapa();
    e.id = UUID.randomUUID();
    e.tenantId = tenantId;
    e.codigo = codigo;
    e.nombre = nombre;
    e.etiquetaMatriz = etiquetaMatriz;
    e.orden = orden;
    e.textoSugerido = textoSugerido;
    e.activo = true;
    return e;
  }

  public String getCodigo() {
    return codigo;
  }

  public String getNombre() {
    return nombre;
  }

  public String getEtiquetaMatriz() {
    return etiquetaMatriz;
  }

  /** Lo que se escribe en la matriz de la entidad. */
  public String etiquetaParaMatriz() {
    return etiquetaMatriz == null || etiquetaMatriz.isBlank() ? nombre : etiquetaMatriz;
  }

  public int getOrden() {
    return orden;
  }

  public String getTextoSugerido() {
    return textoSugerido;
  }

  public boolean isActivo() {
    return activo;
  }
}
