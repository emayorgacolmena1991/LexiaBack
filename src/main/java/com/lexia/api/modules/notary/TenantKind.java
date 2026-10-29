package com.lexia.api.modules.notary;

/** Clasificación del tenant en control.tenant (opción A: notaría siempre es tenant aparte). */
public enum TenantKind {
  LEGAL_FIRM,
  NOTARY,
  PLATFORM,
  OTHER;

  public static TenantKind fromDb(String value) {
    if (value == null || value.isBlank()) {
      return LEGAL_FIRM;
    }
    return TenantKind.valueOf(value.trim().toUpperCase());
  }
}
