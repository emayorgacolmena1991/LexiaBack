package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_note")
public class CaseNote {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "author_user_id")
  private UUID authorUserId;

  @Column(nullable = false)
  private String content;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public static CaseNote create(UUID tenantId, UUID caseId, UUID authorUserId, String content) {
    CaseNote note = new CaseNote();
    note.id = UUID.randomUUID();
    note.tenantId = tenantId;
    note.caseId = caseId;
    note.authorUserId = authorUserId;
    note.content = content;
    note.createdAt = Instant.now();
    return note;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAuthorUserId() {
    return authorUserId;
  }

  public String getContent() {
    return content;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
