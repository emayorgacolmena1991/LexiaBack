package com.lexia.api.modules.expedientes.cierre;

import java.util.ArrayList;
import java.util.List;

/**
 * Correo de observaciones al afiliado: plantilla por tenant (texto modelo de la abogada por
 * defecto, detalle documental sección 4) con el producto y las observaciones abiertas agrupadas.
 * Solo se muestra para copiar.
 */
public final class CorreoAfiliado {

  /** Un bloque del detalle: documento observado, documentos faltantes u otras observaciones. */
  public record Grupo(String titulo, List<String> lineas) {}

  public record Firma(String estudio, String abogado, String telefono, String correo) {}

  public static final String MARCA_ESTUDIO = "{estudio}";
  public static final String MARCA_PRODUCTO = "{producto}";
  public static final String MARCA_OBSERVACIONES = "{observaciones}";
  public static final String MARCA_FIRMA = "{firma}";

  public static final String PLANTILLA_POR_DEFECTO =
      """
      Buen día estimad@ afiliad@,

      Reciba un cordial saludo de parte del Estudio Jurídico {estudio}.

      Por medio del presente le informamos que el Banco del Instituto Ecuatoriano de Seguridad \
      Social (BIESS) nos ha asignado su trámite para «ESTUDIO DE TÍTULO» correspondiente a la \
      primera etapa de su proceso de «{producto}».

      Tras la revisión de la documentación escaneada y cargada en la plataforma Logiflow-BIESS, se \
      han identificado observaciones y/o documentos pendientes, los cuales deberán ser enviados \
      únicamente por correo electrónico o WhatsApp, detallo:

      {observaciones}

      Nota: No se requiere presencia física, ni en el estudio jurídico ni en el BIESS para entregar \
      la documentación solicitada.

      Es importante considerar que el trámite debe cumplir previamente la fase de inspección del \
      inmueble, a cargo del perito colaborador del BIESS:

        • Si el informe pericial es favorable y el BIESS aprueba el préstamo, el trámite continúa.
        • Si el informe es negativo, el vendedor deberá realizar las adecuaciones necesarias al \
      inmueble y justificarlas ante el BIESS para la aprobación del crédito.
        • Una vez superada esta fase, el BIESS entregará al Estudio Jurídico la carpeta física con \
      los documentos originales proporcionados por usted a la entidad.

      Mientras se realizan estas gestiones por parte del banco, solicitamos su apoyo con la \
      siguiente información para adelantar con la realización de los contratos para la revisión de \
      las partes (comprador/vendedor):

      En cuanto a la cuantía de la compraventa, debe indicar:

        • Valor total de la compraventa (igual o mayor al monto financiado por el BIESS). En caso de \
      ser mayor y de haberse dado anticipo, indicar el monto y forma de pago (transferencia, cheque \
      o efectivo).
        • Adjuntar comprobante del anticipo, el cual es obligatorio y se adjunta a la escritura como \
      respaldo de ambas partes.

      Información personal de las partes:

        • Profesión (compradores/vendedor)
        • Dirección domiciliaria completa actual (compradores/vendedor)
        • Si el afiliado es casado, indicar el correo electrónico del cónyuge no solicitante del \
      crédito.

      Finalmente, una vez que el préstamo hipotecario sea aprobado por el BIESS y el trámite sea \
      enviado nuevamente a nuestro sistema por la entidad, inicia la instrumentación legal.

      Aclaración importante sobre la instrumentación legal:

      La instrumentación legal no es inmediata y se desarrolla por etapas obligatorias.

      Agradecemos la paciencia, y quedamos atentos a su colaboración y a cualquier inquietud \
      adicional.

      Atentamente,

      {firma}
      """;

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
    return texto(producto, grupos, firma, null);
  }

  /** {@code plantilla} vacía = la del estudio por defecto. */
  public static String texto(String producto, List<Grupo> grupos, Firma firma, String plantilla) {
    Firma f = firma == null ? new Firma("", "", "", "") : firma;
    String base = v(plantilla).isEmpty() ? PLANTILLA_POR_DEFECTO : plantilla;
    String estudio = v(f.estudio());
    String texto =
        base.replace(" " + MARCA_ESTUDIO, estudio.isEmpty() ? "" : " " + estudio)
            .replace(MARCA_ESTUDIO, estudio)
            .replace(MARCA_PRODUCTO, v(producto).isEmpty() ? "CRÉDITO HIPOTECARIO" : v(producto))
            .replace(MARCA_OBSERVACIONES, observaciones(grupos))
            .replace(MARCA_FIRMA, firma(f));
    return texto.stripTrailing() + "\n";
  }

  static String observaciones(List<Grupo> grupos) {
    List<Grupo> conLineas = new ArrayList<>();
    for (Grupo g : grupos == null ? List.<Grupo>of() : grupos) {
      if (g != null && g.lineas() != null && !g.lineas().isEmpty()) {
        conLineas.add(g);
      }
    }
    if (conLineas.isEmpty()) {
      return "No se registran observaciones abiertas en este momento.";
    }
    StringBuilder sb = new StringBuilder();
    for (Grupo g : conLineas) {
      if (!sb.isEmpty()) {
        sb.append("\n");
      }
      sb.append(v(g.titulo())).append(":\n");
      for (String linea : g.lineas()) {
        sb.append("  • ").append(v(linea)).append("\n");
      }
    }
    return sb.toString().stripTrailing();
  }

  static String firma(Firma f) {
    List<String> lineas = new ArrayList<>();
    if (!v(f.abogado()).isEmpty()) {
      lineas.add("Ab. " + v(f.abogado()));
    }
    if (!v(f.telefono()).isEmpty()) {
      lineas.add("Teléfono: " + v(f.telefono()));
    }
    if (!v(f.correo()).isEmpty()) {
      lineas.add("Correo: " + v(f.correo()));
    }
    return String.join("\n", lineas);
  }

  private static String v(String s) {
    return s == null ? "" : s.trim();
  }
}
