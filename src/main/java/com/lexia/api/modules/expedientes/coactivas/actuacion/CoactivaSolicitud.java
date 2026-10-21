package com.lexia.api.modules.expedientes.coactivas.actuacion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "coactiva_solicitud")
public class CoactivaSolicitud implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "expediente_id", nullable = false)
  private UUID expedienteId;

  @Column(nullable = false, length = 32)
  private String operacion;

  @Column(columnDefinition = "text")
  private String detalle;

  @Column(name = "idempotency_key", length = 120)
  private String idempotencyKey;

  @Column(name = "resultado_http", nullable = false)
  private int resultadoHttp;

  @Column(name = "resultado_code", nullable = false, length = 64)
  private String resultadoCode;

  @Column(name = "resultado_message", nullable = false)
  private String resultadoMessage;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CoactivaSolicitud crear(
      UUID tenantId,
      UUID expedienteId,
      String operacion,
      String detalle,
      String idempotencyKey,
      int http,
      String code,
      String message,
      UUID createdBy) {
    CoactivaSolicitud solicitud = new CoactivaSolicitud();
    solicitud.id = UUID.randomUUID();
    solicitud.tenantId = tenantId;
    solicitud.expedienteId = expedienteId;
    solicitud.operacion = operacion;
    solicitud.detalle = detalle;
    solicitud.idempotencyKey = idempotencyKey;
    solicitud.resultadoHttp = http;
    solicitud.resultadoCode = code;
    solicitud.resultadoMessage = message;
    solicitud.createdBy = createdBy;
    solicitud.createdAt = Instant.now();
    return solicitud;
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

  public String getDetalle() {
    return detalle;
  }

  public int getResultadoHttp() {
    return resultadoHttp;
  }

  public String getResultadoCode() {
    return resultadoCode;
  }

  public String getResultadoMessage() {
    return resultadoMessage;
  }
}
