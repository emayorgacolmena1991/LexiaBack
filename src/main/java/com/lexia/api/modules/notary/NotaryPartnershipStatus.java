package com.lexia.api.modules.notary;

public enum NotaryPartnershipStatus {
  PENDING,
  ACTIVE,
  SUSPENDED,
  REJECTED;

  public static NotaryPartnershipStatus fromDb(String value) {
    return NotaryPartnershipStatus.valueOf(value.trim().toUpperCase());
  }
}
