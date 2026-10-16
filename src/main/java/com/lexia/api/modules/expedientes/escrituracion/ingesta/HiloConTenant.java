package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.tenancy.RlsConnectionProvider;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.UUID;
import java.util.function.Supplier;

/** El pool de OCR no hereda el ThreadLocal del worker. Hay que copiarlo en el hilo que toca la BD. */
final class HiloConTenant {

  private HiloConTenant() {}

  static Runnable copiar(Runnable trabajo) {
    Supplier<Void> envuelto =
        copiar(
            () -> {
              trabajo.run();
              return null;
            });
    return envuelto::get;
  }

  static <T> Supplier<T> copiar(Supplier<T> trabajo) {
    AuthPrincipal auth = AuthContext.get();
    UUID tenant = TenantContext.getTenantId();
    return () -> {
      if (auth != null) {
        AuthContext.set(auth);
      }
      if (tenant != null) {
        RlsConnectionProvider.setTenantId(tenant);
      }
      try {
        return trabajo.get();
      } finally {
        AuthContext.clear();
        TenantContext.clear();
      }
    };
  }
}
