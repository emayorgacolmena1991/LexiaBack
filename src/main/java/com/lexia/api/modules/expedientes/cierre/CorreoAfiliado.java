package com.lexia.api.modules.expedientes.cierre;

import java.util.ArrayList;
import java.util.List;

/**
 * Correo de observaciones al afiliado: texto modelo de la abogada (detalle documental, sección 4)
 * con el producto y las observaciones abiertas agrupadas. Solo se muestra para copiar.
 */
public final class CorreoAfiliado {

  /** Un bloque del detalle: documento observado, documentos faltantes u otras observaciones. */
  public record Grupo(String titulo, List<String> lineas) {}

  public record Firma(String estudio, String abogado, String telefono, String correo) {}

  private CorreoAfiliado() {}

  public static String asunto(String producto, String nip) {
    StringBuilder sb = new StringBuilder("Observaciones de su trámite BIESS");
    if (!v(producto).isEmpty()) {
      sb.append(" – ").append(v(producto));
    }
    if (!v(nip).isEmpty()) {
      sb.append(" (NIP ").append(v(nip)).append(")");
    }
    return sb.toString();
  }

  public static String texto(String producto, List<Grupo> grupos, Firma firma) {
    Firma f = firma == null ? new Firma("", "", "", "") : firma;
    String estudio = v(f.estudio()).isEmpty() ? "" : " " + v(f.estudio());
    StringBuilder sb = new StringBuilder();
    sb.append("Buen día estimad@ afiliad@,\n\n");
    sb.append("Reciba un cordial saludo de parte del Estudio Jurídico").append(estudio).append(".\n\n");
    sb.append(
            "Por medio del presente le informamos que el Banco del Instituto Ecuatoriano de Seguridad"
                + " Social (BIESS) nos ha asignado su trámite para «ESTUDIO DE TÍTULO» correspondiente a"
                + " la primera etapa de su proceso de «")
        .append(v(producto).isEmpty() ? "CRÉDITO HIPOTECARIO" : v(producto))
        .append("».\n\n");
    sb.append(
        "Tras la revisión de la documentación escaneada y cargada en la plataforma Logiflow-BIESS, se"
            + " han identificado observaciones y/o documentos pendientes, los cuales deberán ser"
            + " enviados únicamente por correo electrónico o WhatsApp, detallo:\n\n");
    List<Grupo> conLineas = new ArrayList<>();
    for (Grupo g : grupos == null ? List.<Grupo>of() : grupos) {
      if (g != null && g.lineas() != null && !g.lineas().isEmpty()) {
        conLineas.add(g);
      }
    }
    if (conLineas.isEmpty()) {
      sb.append("No se registran observaciones abiertas en este momento.\n\n");
    }
    for (Grupo g : conLineas) {
      sb.append(v(g.titulo())).append(":\n");
      for (String linea : g.lineas()) {
        sb.append("  • ").append(v(linea)).append("\n");
      }
      sb.append("\n");
    }
    sb.append(
        "Nota: No se requiere presencia física, ni en el estudio jurídico ni en el BIESS para entregar"
            + " la documentación solicitada.\n\n");
    sb.append(
        "Es importante considerar que el trámite debe cumplir previamente la fase de inspección del"
            + " inmueble, a cargo del perito colaborador del BIESS:\n\n");
    sb.append(
        "  • Si el informe pericial es favorable y el BIESS aprueba el préstamo, el trámite"
            + " continúa.\n");
    sb.append(
        "  • Si el informe es negativo, el vendedor deberá realizar las adecuaciones necesarias al"
            + " inmueble y justificarlas ante el BIESS para la aprobación del crédito.\n");
    sb.append(
        "  • Una vez superada esta fase, el BIESS entregará al Estudio Jurídico la carpeta física con"
            + " los documentos originales proporcionados por usted a la entidad.\n\n");
    sb.append(
        "Mientras se realizan estas gestiones por parte del banco, solicitamos su apoyo con la"
            + " siguiente información para adelantar con la realización de los contratos para la"
            + " revisión de las partes (comprador/vendedor):\n\n");
    sb.append("En cuanto a la cuantía de la compraventa, debe indicar:\n\n");
    sb.append(
        "  • Valor total de la compraventa (igual o mayor al monto financiado por el BIESS). En caso"
            + " de ser mayor y de haberse dado anticipo, indicar el monto y forma de pago"
            + " (transferencia, cheque o efectivo).\n");
    sb.append(
        "  • Adjuntar comprobante del anticipo, el cual es obligatorio y se adjunta a la escritura"
            + " como respaldo de ambas partes.\n\n");
    sb.append("Información personal de las partes:\n\n");
    sb.append("  • Profesión (compradores/vendedor)\n");
    sb.append("  • Dirección domiciliaria completa actual (compradores/vendedor)\n");
    sb.append(
        "  • Si el afiliado es casado, indicar el correo electrónico del cónyuge no solicitante del"
            + " crédito.\n\n");
    sb.append(
        "Finalmente, una vez que el préstamo hipotecario sea aprobado por el BIESS y el trámite sea"
            + " enviado nuevamente a nuestro sistema por la entidad, inicia la instrumentación"
            + " legal.\n\n");
    sb.append("Aclaración importante sobre la instrumentación legal:\n\n");
    sb.append("La instrumentación legal no es inmediata y se desarrolla por etapas obligatorias.\n\n");
    sb.append(
        "Agradecemos la paciencia, y quedamos atentos a su colaboración y a cualquier inquietud"
            + " adicional.\n\n");
    sb.append("Atentamente,\n\n");
    if (!v(f.abogado()).isEmpty()) {
      sb.append("Ab. ").append(v(f.abogado())).append("\n");
    }
    if (!v(f.telefono()).isEmpty()) {
      sb.append("Teléfono: ").append(v(f.telefono())).append("\n");
    }
    if (!v(f.correo()).isEmpty()) {
      sb.append("Correo: ").append(v(f.correo())).append("\n");
    }
    return sb.toString().stripTrailing() + "\n";
  }

  private static String v(String s) {
    return s == null ? "" : s.trim();
  }
}
