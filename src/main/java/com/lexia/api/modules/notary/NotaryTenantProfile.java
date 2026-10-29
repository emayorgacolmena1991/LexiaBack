package com.lexia.api.modules.notary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "control", name = "notary_tenant_profile")
public class NotaryTenantProfile {

  @Id
  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(nullable = false, length = 20)
  private String ruc;

  @Column(nullable = false, length = 120)
  private String canton;

  @Column(name = "notary_number", length = 32)
  private String notaryNumber;

  @Column(name = "official_name", nullable = false, length = 240)
  private String officialName;

  @Column(name = "notary_holder_name", length = 200)
  private String notaryHolderName;

  @Column(name = "contact_email", length = 320)
  private String contactEmail;

  @Column(nullable = false)
  private boolean listed;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public UUID getTenantId() {
    return tenantId;
  }

  public String getRuc() {
    return ruc;
  }

  public String getCanton() {
    return canton;
  }

  public String getNotaryNumber() {
    return notaryNumber;
  }

  public String getOfficialName() {
    return officialName;
  }

  public String getNotaryHolderName() {
    return notaryHolderName;
  }

  public String getContactEmail() {
    return contactEmail;
  }

  public boolean isListed() {
    return listed;
  }

  public static NotaryTenantProfile create(
      UUID tenantId,
      String ruc,
      String canton,
      String notaryNumber,
      String officialName,
      String notaryHolderName,
      String contactEmail,
      boolean listed) {
    NotaryTenantProfile profile = new NotaryTenantProfile();
    profile.tenantId = tenantId;
    profile.ruc = ruc.trim();
    profile.canton = canton.trim();
    profile.notaryNumber =
        notaryNumber != null && !notaryNumber.isBlank() ? notaryNumber.trim() : null;
    profile.officialName = officialName.trim();
    profile.notaryHolderName =
        notaryHolderName != null && !notaryHolderName.isBlank() ? notaryHolderName.trim() : null;
    profile.contactEmail =
        contactEmail != null && !contactEmail.isBlank() ? contactEmail.trim().toLowerCase() : null;
    profile.listed = listed;
    Instant now = Instant.now();
    profile.createdAt = now;
    profile.updatedAt = now;
    return profile;
  }
}
