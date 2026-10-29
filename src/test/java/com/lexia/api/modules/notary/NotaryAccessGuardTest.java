package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexia.api.modules.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotaryAccessGuardTest {

  private final NotaryAccessGuard guard = new NotaryAccessGuard();

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void permiteBufeteONotariaDeLaRemision() {
    UUID firm = UUID.randomUUID();
    UUID notary = UUID.randomUUID();
    NotaryRemision remision =
        NotaryRemision.create(
            firm, notary, UUID.randomUUID(), null, "X", "Y", "Acta", 1, null, null);

    TenantContext.setTenantId(firm);
    assertThatCode(() -> guard.requireRemisionAccess(remision)).doesNotThrowAnyException();

    TenantContext.setTenantId(notary);
    assertThatCode(() -> guard.requireRemisionAccess(remision)).doesNotThrowAnyException();
  }

  @Test
  void rechazaTenantAjeno() {
    NotaryRemision remision =
        NotaryRemision.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "X",
            "Y",
            "Acta",
            1,
            null,
            null);
    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> guard.requireRemisionAccess(remision))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sin acceso");
  }
}
