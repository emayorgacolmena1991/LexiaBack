package com.lexia.api.modules.ia;

import org.springframework.util.StringUtils;

/**
 * Traduce fallos técnicos del LLM (Anthropic, Gemini, etc.) a códigos operativos y textos
 * aptos para usuario final y administración.
 */
public final class ProveedorIaErrores {

  private ProveedorIaErrores() {}

  public record Clasificacion(
      String codigo,
      String mensajeUsuario,
      String mensajeAdminResumen,
      boolean falloProveedor,
      boolean notificarAdministradores) {}

  private static final String MSG_USUARIO_PROVEEDOR =
      "El análisis con IA no pudo completarse por un problema de comunicación con el proveedor "
          + "de inteligencia artificial. Sus documentos no están necesariamente en error. "
          + "Comuníquese con el administrador de su organización o con la mesa de ayuda / proveedor de servicios Lexia.";

  private static final String MSG_USUARIO_GENERICO =
      "El análisis con IA no pudo completarse por un error técnico. "
          + "Si el problema continúa, contacte al administrador de su organización o a la mesa de ayuda.";

  public static Clasificacion clasificar(String motivoTecnico) {
    String raw = motivoTecnico == null ? "" : motivoTecnico.trim();
    String lower = raw.toLowerCase();

    if (lower.contains("credit balance")
        || lower.contains("insufficient")
        || lower.contains("billing")
        || lower.contains("purchase credits")
        || lower.contains("quota exceeded")
        || lower.contains("exceeded your current quota")) {
      return new Clasificacion(
          "PROVEEDOR_SIN_CREDITO",
          MSG_USUARIO_PROVEEDOR,
          "Saldo o cupo del proveedor IA agotado.",
          true,
          true);
    }
    if (lower.contains("invalid_request_error")
        || lower.contains("authentication")
        || lower.contains("invalid api key")
        || lower.contains("401")
        || lower.contains("403 forbidden")) {
      return new Clasificacion(
          "PROVEEDOR_CONFIGURACION",
          MSG_USUARIO_PROVEEDOR,
          "Credenciales o configuración del proveedor IA.",
          true,
          true);
    }
    if (lower.contains("overloaded")
        || lower.contains("529")
        || lower.contains("rate limit")
        || lower.contains("too many requests")
        || lower.contains("429")) {
      return new Clasificacion(
          "PROVEEDOR_SATURADO",
          "El proveedor de IA está saturado. Espere unos minutos e intente de nuevo. "
              + "Si persiste, contacte al administrador de su organización.",
          "Proveedor IA saturado o límite de tasa.",
          true,
          true);
    }
    if (lower.contains("timeout")
        || lower.contains("timed out")
        || lower.contains("deadline")
        || lower.contains("cancelado por timeout")) {
      return new Clasificacion(
          "PROVEEDOR_TIMEOUT",
          "El análisis con IA excedió el tiempo máximo. Puede reintentar el cotejo. "
              + "Si se repite, contacte al administrador.",
          "Tiempo de espera agotado hacia el proveedor IA.",
          true,
          false);
    }
    if (lower.contains("connection")
        || lower.contains("connect")
        || lower.contains("unreachable")
        || lower.contains("socket")
        || lower.contains("502")
        || lower.contains("503")
        || lower.contains("504")) {
      return new Clasificacion(
          "PROVEEDOR_RED",
          MSG_USUARIO_PROVEEDOR,
          "Fallo de red hacia el proveedor IA.",
          true,
          true);
    }
    if (raw.startsWith("modelo=") || lower.contains("anthropic") || lower.contains("claude") || lower.contains("gemini")) {
      return new Clasificacion(
          "PROVEEDOR_RESPUESTA",
          MSG_USUARIO_PROVEEDOR,
          recortarAdmin(raw),
          true,
          true);
    }
    return new Clasificacion(
        "IA_ERROR_TECNICO",
        MSG_USUARIO_GENERICO,
        StringUtils.hasText(raw) ? recortarAdmin(raw) : "Error técnico sin detalle.",
        false,
        false);
  }

  /** Si conviene intentar el otro proveedor LLM (p. ej. créditos agotados en el primario). */
  public static boolean admiteFailoverProveedor(String motivoTecnico) {
    Clasificacion c = clasificar(motivoTecnico);
    return c.falloProveedor() && !"PROVEEDOR_CONFIGURACION".equals(c.codigo());
  }

  private static String recortarAdmin(String raw) {
    if (raw.length() <= 160) {
      return raw;
    }
    return raw.substring(0, 157) + "…";
  }
}