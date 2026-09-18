package com.lexia.api.modules.auth;

public final class AuthContext {

  private static final ThreadLocal<AuthPrincipal> CURRENT = new ThreadLocal<>();

  private AuthContext() {}

  public static void set(AuthPrincipal principal) {
    CURRENT.set(principal);
  }

  public static AuthPrincipal require() {
    AuthPrincipal principal = CURRENT.get();
    if (principal == null) {
      throw AuthException.unauthorized("Sesión no válida o vencida.");
    }
    return principal;
  }

  public static AuthPrincipal get() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
