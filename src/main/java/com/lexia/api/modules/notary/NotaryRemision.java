package com.lexia.api.modules.notary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "control", name = "notary_remision")
public class NotaryRemision {

  @Id private UUID id;

  @Column(name = "firm_tenant_id", nullable = false)
  private UUID firmTenantId;

  @Column(name = "notary_tenant_id", nullable = false)
  private UUID notaryTenantId;

  @Column(name = "firm_legal_case_id", nullable = false)
  private UUID firmLegalCaseId;

  @Column(name = "firm_writing_file_id")
  private UUID firmWritingFileId;

  @Column(name = "expediente_codigo", nullable = false, length = 80)
  private String expedienteCodigo;

  @Column(length = 500)
  private String asunto;

  @Column(nullable = false, length = 32)
  private String estado;

  @Column(name = "paso_notarial", nullable = false)
  private short pasoNotarial;

  @Column(name = "acta_label", length = 500)
  private String actaLabel;

  @Column(name = "documentos_count", nullable = false)
  private int documentosCount;

  @Column(name = "emitido_por_user_id")
  private UUID emitidoPorUserId;

  @Column(name = "emitido_en", nullable = false)
  private Instant emitidoEn;

  @Column(name = "recibido_en")
  private Instant recibidoEn;

  @Column(name = "observaciones_bufete")
  private String observacionesBufete;

  @Column(name = "observaciones_notaria")
  private String observacionesNotaria;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  protected NotaryRemision() {}

  public static NotaryRemision create(
      UUID firmTenantId,
      UUID notaryTenantId,
      UUID firmLegalCaseId,
      UUID firmWritingFileId,
      String expedienteCodigo,
      String asunto,
      String actaLabel,
      int documentosCount,
      UUID emitidoPorUserId,
      String observacionesBufete) {
    NotaryRemision r = new NotaryRemision();
    Instant now = Instant.now();
    r.id = UUID.randomUUID();
    r.firmTenantId = firmTenantId;
    r.notaryTenantId = notaryTenantId;
    r.firmLegalCaseId = firmLegalCaseId;
    r.firmWritingFileId = firmWritingFileId;
    r.expedienteCodigo = expedienteCodigo;
    r.asunto = asunto;
    r.estado = NotaryRemisionEstado.ENVIADO_A_NOTARIA.name();
    r.pasoNotarial = 0;
    r.actaLabel = actaLabel;
    r.documentosCount = documentosCount;
    r.emitidoPorUserId = emitidoPorUserId;
    r.emitidoEn = now;
    r.observacionesBufete = observacionesBufete;
    r.createdAt = now;
    r.updatedAt = now;
    r.rowVersion = 1L;
    return r;
  }

  public void acknowledgeReceipt() {
    if (NotaryRemisionEstado.ENVIADO_A_NOTARIA.name().equals(estado)) {
      estado = NotaryRemisionEstado.RECIBIDO.name();
      recibidoEn = Instant.now();
      touch();
    }
  }

  public void advanceStep() {
    int next = Math.min(pasoNotarial + 1, NotaryProcessSteps.COUNT - 1);
    pasoNotarial = (short) next;
    if (next == 0) {
      estado = NotaryRemisionEstado.RECIBIDO.name();
    } else if (next < NotaryProcessSteps.COUNT - 1) {
      estado = NotaryRemisionEstado.EN_TRAMITE.name();
    } else {
      estado = NotaryRemisionEstado.NOTARIA_COMPLETADA.name();
    }
    touch();
  }

  public void devolverAlBufete(String observaciones) {
    estado = NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name();
    if (observaciones != null && !observaciones.isBlank()) {
      observacionesNotaria = observaciones.trim();
    }
    touch();
  }

  private void touch() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getFirmTenantId() {
    return firmTenantId;
  }

  public UUID getNotaryTenantId() {
    return notaryTenantId;
  }

  public UUID getFirmLegalCaseId() {
    return firmLegalCaseId;
  }

  public UUID getFirmWritingFileId() {
    return firmWritingFileId;
  }

  public String getExpedienteCodigo() {
    return expedienteCodigo;
  }

  public String getAsunto() {
    return asunto;
  }

  public String getEstado() {
    return estado;
  }

  public NotaryRemisionEstado estadoEnum() {
    return NotaryRemisionEstado.fromDb(estado);
  }

  public short getPasoNotarial() {
    return pasoNotarial;
  }

  public String getActaLabel() {
    return actaLabel;
  }

  public int getDocumentosCount() {
    return documentosCount;
  }

  public UUID getEmitidoPorUserId() {
    return emitidoPorUserId;
  }

  public Instant getEmitidoEn() {
    return emitidoEn;
  }

  public Instant getRecibidoEn() {
    return recibidoEn;
  }

  public String getObservacionesBufete() {
    return observacionesBufete;
  }

  public String getObservacionesNotaria() {
    return observacionesNotaria;
  }
}
