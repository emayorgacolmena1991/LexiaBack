package com.lexia.api.modules.expedientes.documentos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "document")
public class LegalDocument implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id")
  private UUID caseId;

  @Column(nullable = false, length = 320)
  private String name;

  @Column(name = "doc_type", length = 80)
  private String docType;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "tipo_sugerido", length = 80)
  private String tipoSugerido;

  @Column(name = "clasificacion_confianza", precision = 4, scale = 3)
  private BigDecimal clasificacionConfianza;

  @Column(name = "clasificacion_origen", length = 16)
  private String clasificacionOrigen;

  @Column(name = "clasificacion_estado", nullable = false, length = 16)
  private String clasificacionEstado = ESTADO_PENDIENTE;

  @Column(name = "clasificacion_modelo", length = 80)
  private String clasificacionModelo;

  @Column(name = "clasificado_en")
  private Instant clasificadoEn;

  @Column(name = "uploaded_by")
  private UUID uploadedBy;

  @Column(length = 500)
  private String observacion;

  /** Descripción que escribe el usuario para un documento OTRO (V120). */
  @Column(name = "descripcion_tipo", length = MAX_DESCRIPCION_OTRO)
  private String descripcionTipo;

  /** Persona a la que pertenece (cédula, papeleta, nombramiento, poder) (V121). */
  @Column(name = "compareciente_id")
  private UUID comparecienteId;

  /**
   * EJ-2: la trae el tercero (vendedor persona jurídica). No entra al consolidado del expediente;
   * EJ-3 la revisa aparte ({@code RevisionMinutaTerceroService}).
   */
  public static final String TIPO_MINUTA_TERCERO = "MINUTA_COMPRAVENTA_TERCERO";

  /** EJ-4: roles de pago / soportes de ingresos; opcional en productos con compraventa. */
  public static final String TIPO_SOPORTE_INGRESOS = "SOPORTE_INGRESOS";

  /**
   * Se guardan y viajan a la notaría; no entran al OCR/IA del cotejo del expediente. La minuta de
   * tercero tiene revisión IA dedicada (EJ-3); SOPORTE_INGRESOS no.
   */
  public static boolean esSoloSoporte(String codigo) {
    return TIPO_MINUTA_TERCERO.equals(codigo) || TIPO_SOPORTE_INGRESOS.equals(codigo);
  }

  /**
   * Documento que no está en el catálogo: vale para todo producto y nunca es requisito. Su
   * descripción (la escribe el usuario) va en {@code descripcion_tipo}.
   */
  public static final String TIPO_OTRO = "OTRO";

  public static final int MAX_DESCRIPCION_OTRO = 120;

  /**
   * Descripción obligatoria de OTRO, recortada; {@code null} para cualquier otro tipo.
   *
   * @throws ApiException 400 si es OTRO y falta la descripción o pasa de 120 caracteres.
   */
  public static String descripcionOtro(String codigo, String descripcion) {
    if (!TIPO_OTRO.equals(codigo)) {
      return null;
    }
    String texto = descripcion == null ? "" : descripcion.trim().replaceAll("\\s+", " ");
    if (texto.isEmpty()) {
      throw com.lexia.api.common.api.ApiException.badRequest(
          "Describe brevemente el documento de tipo Otro.");
    }
    if (texto.length() > MAX_DESCRIPCION_OTRO) {
      throw com.lexia.api.common.api.ApiException.badRequest(
          "La descripción del documento admite hasta " + MAX_DESCRIPCION_OTRO + " caracteres.");
    }
    return texto;
  }

  public static final String ORIGEN_AUTO = "AUTO";
  public static final String ORIGEN_MANUAL = "MANUAL";
  public static final String ESTADO_PENDIENTE = "PENDIENTE";
  public static final String ESTADO_CLASIFICADO = "CLASIFICADO";
  public static final String ESTADO_POR_REVISAR = "POR_REVISAR";
  public static final String ESTADO_ERROR = "ERROR";

  public static LegalDocument create(UUID tenantId, UUID caseId, String name, String docType) {
    LegalDocument doc = new LegalDocument();
    doc.id = UUID.randomUUID();
    doc.tenantId = tenantId;
    doc.caseId = caseId;
    doc.name = name;
    doc.docType = docType;
    Instant now = Instant.now();
    doc.createdAt = now;
    doc.updatedAt = now;
    doc.isNew = true;
    return doc;
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public String getName() {
    return name;
  }

  public String getDocType() {
    return docType;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void rename(String name, String docType) {
    if (name != null && !name.isBlank()) {
      this.name = name;
    }
    this.docType = docType;
    this.updatedAt = Instant.now();
  }

  /**
   * Tipo elegido por el usuario con su descripción si es OTRO ({@code null} conserva la que había).
   * Al salir de OTRO se borra la descripción.
   */
  public void classify(String docType, String descripcionOtro) {
    boolean eraOtro = TIPO_OTRO.equals(this.docType);
    classify(docType);
    if (TIPO_OTRO.equals(docType)) {
      if (descripcionOtro != null) {
        this.descripcionTipo = descripcionOtro;
      }
    } else if (eraOtro) {
      this.descripcionTipo = null;
    }
  }

  /** Tipo elegido por el usuario (PATCH, wizard, ingesta masiva): ninguna clasificación AUTO lo pisa. */
  public void classify(String docType) {
    Instant now = Instant.now();
    this.docType = docType;
    this.clasificacionOrigen = ORIGEN_MANUAL;
    this.clasificacionEstado = ESTADO_CLASIFICADO;
    this.clasificadoEn = now;
    this.updatedAt = now;
  }

  /**
   * MANUAL, o un tipo previo a la clasificación automática (sin origen): lo puso una persona.
   */
  public boolean tipoFijadoPorUsuario() {
    return ORIGEN_MANUAL.equals(clasificacionOrigen)
        || (clasificacionOrigen == null && docType != null && !docType.isBlank());
  }

  /**
   * Resultado del clasificador. {@code asignar=true} solo si superó el umbral y el tipo no está
   * fijado por el usuario; si no, queda como sugerencia con el estado recibido.
   */
  public void registrarClasificacionAuto(
      String tipo, BigDecimal confianza, String modelo, String estado, boolean asignar) {
    Instant now = Instant.now();
    this.tipoSugerido = tipo;
    this.clasificacionConfianza = confianza;
    this.clasificacionModelo = modelo;
    this.clasificacionEstado = estado;
    this.clasificadoEn = now;
    if (asignar && !tipoFijadoPorUsuario()) {
      this.docType = tipo;
      this.clasificacionOrigen = ORIGEN_AUTO;
    }
    this.updatedAt = now;
  }

  /** Promoción del borrador: el documento nace con la clasificación que ya tenía, sin reclasificar. */
  public void copiarClasificacion(
      String docType,
      String tipoSugerido,
      BigDecimal confianza,
      String origen,
      String estado,
      String modelo,
      Instant clasificadoEn) {
    this.docType = docType;
    this.tipoSugerido = tipoSugerido;
    this.clasificacionConfianza = confianza;
    this.clasificacionOrigen = origen;
    this.clasificacionEstado = estado == null ? ESTADO_PENDIENTE : estado;
    this.clasificacionModelo = modelo;
    this.clasificadoEn = clasificadoEn;
    this.updatedAt = Instant.now();
  }

  /** El clasificador falló: el usuario clasifica a mano. No toca el tipo ni el origen. */
  public void registrarErrorClasificacion(String modelo) {
    Instant now = Instant.now();
    this.clasificacionEstado = ESTADO_ERROR;
    this.clasificacionModelo = modelo;
    this.clasificadoEn = now;
    this.updatedAt = now;
  }

  public String getTipoSugerido() {
    return tipoSugerido;
  }

  public BigDecimal getClasificacionConfianza() {
    return clasificacionConfianza;
  }

  public String getClasificacionOrigen() {
    return clasificacionOrigen;
  }

  public String getClasificacionEstado() {
    return clasificacionEstado;
  }

  public String getClasificacionModelo() {
    return clasificacionModelo;
  }

  public Instant getClasificadoEn() {
    return clasificadoEn;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public String getObservacion() {
    return observacion;
  }

  public String getDescripcionTipo() {
    return descripcionTipo;
  }

  public UUID getComparecienteId() {
    return comparecienteId;
  }

  /** null desliga. */
  public void ligarCompareciente(UUID comparecienteId) {
    this.comparecienteId = comparecienteId;
    this.updatedAt = Instant.now();
  }

  /** Promoción del borrador: la descripción de OTRO viaja con el documento. */
  public void describirOtro(String descripcion) {
    if (TIPO_OTRO.equals(docType)) {
      this.descripcionTipo = descripcion;
      this.updatedAt = Instant.now();
    }
  }

  public void registrarCarga(UUID uploadedBy, String observacion) {
    this.uploadedBy = uploadedBy;
    this.observacion = observacion;
  }

  public void softDelete() {
    Instant now = Instant.now();
    this.deletedAt = now;
    this.updatedAt = now;
  }
}
