package com.lexia.api.modules.expedientes.caso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_party")
public class CaseParty {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(nullable = false, length = 16)
  private String kind;

  @Column(name = "display_name", nullable = false, length = 240)
  private String displayName;

  @Column(name = "role_label", length = 160)
  private String roleLabel;

  @Column(length = 64)
  private String identification;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public static CaseParty create(
      UUID tenantId,
      UUID caseId,
      String kind,
      String displayName,
      String roleLabel,
      String identification) {
    CaseParty party = new CaseParty();
    party.id = UUID.randomUUID();
    party.tenantId = tenantId;
    party.caseId = caseId;
    party.kind = kind;
    party.displayName = displayName;
    party.roleLabel = roleLabel;
    party.identification = identification;
    party.createdAt = Instant.now();
    return party;
  }

  public UUID getId() {
    return id;
  }

  public String getKind() {
    return kind;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getRoleLabel() {
    return roleLabel;
  }

  public String getIdentification() {
    return identification;
  }
}
