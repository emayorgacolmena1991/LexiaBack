package com.lexia.api.modules.notary;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.ClassPathResource;

/**
 * Solicitud de exoneración de impuestos para adultos mayores del GAD de Guayaquil
 * (MP-DF-FIN-GRES-1-F1), escrita sobre el PDF oficial. Lo que trae la remisión llega prellenado;
 * la notaría solo completa lo que falta (como en la escritura) y lo propio del formulario:
 * nacionalidad, pasaporte, impuestos, devolución y declaraciones. Las firmas van a mano.
 *
 * <p>ponytail: coordenadas fijas medidas sobre el formulario actual; si el municipio cambia el PDF
 * hay que volver a medirlas.
 */
final class SolicitudExoneracionPdf {

  static final String PLANTILLA = "templates/notaria/SOLICITUD_EXONERACION_ADULTO_MAYOR_GYE.pdf";
  private static final float TAM = 9;

  static final String TEXTO = "TEXTO";
  static final String SI_NO = "SI_NO";
  static final String CHECK = "CHECK";
  static final String OPCION = "OPCION";
  static final String SI = "SI";
  static final String NO = "NO";

  static final List<String> ESTADOS_CIVILES =
      List.of("SOLTERO(A)", "CASADO(A)", "DIVORCIADO(A)", "VIUDO(A)", "UNIÓN DE HECHO");
  static final String NOTA_CREDITO = "NOTA DE CRÉDITO";
  static final String CUENTA = "ACREDITACIÓN EN CUENTA";

  private static final String S_SOLICITANTE = "Solicitante";
  private static final String S_CONYUGE = "Cónyuge o conviviente";
  private static final String S_IMPUESTOS = "Impuestos a exonerar";
  private static final String S_DEVOLUCION = "Devolución de predial urbano";
  private static final String S_DECL_SOL = "Declaración del solicitante";
  private static final String S_DECL_CONY = "Declaración del cónyuge";

  // Columnas de la tabla de datos (x inicial, ancho útil).
  private static final float X_IZQ = 44;
  private static final float X_PAS = 203;
  private static final float X_DER = 337;
  private static final float ANCHO_IZQ = 288;
  private static final float ANCHO_FECHA = 150;
  private static final float ANCHO_PAS = 130;
  private static final float ANCHO_DER = 220;

  /** Campo del formulario: {@code valor} es el prellenado; "" si no hay. */
  record Campo(
      String clave,
      String seccion,
      String etiqueta,
      String tipo,
      List<String> opciones,
      String valor,
      boolean editable) {}

  private SolicitudExoneracionPdf() {}

  /**
   * Campos del formulario en orden. Lo que sale de la remisión solo es editable si viene vacío;
   * lo propio del formulario siempre lo es.
   *
   * @param solicitante sufijo del comprador de 65+ ({@code conyuge_1} o {@code conyuge_2}); el otro
   *     comprador, si existe, va como cónyuge.
   */
  static List<Campo> campos(JsonNode payload, String solicitante) {
    String conyuge = "conyuge_1".equals(solicitante) ? "conyuge_2" : "conyuge_1";
    boolean hayConyuge = !valor(payload, "nombre_" + conyuge).isEmpty();
    List<Campo> c = new ArrayList<>();
    remision(c, "sol_nombre", S_SOLICITANTE, "Nombres y apellidos", valor(payload, "nombre_" + solicitante));
    remision(c, "sol_cedula", S_SOLICITANTE, "C.I.", valor(payload, "cedula_" + solicitante));
    remision(
        c, "sol_nacimiento", S_SOLICITANTE, "Fecha de nacimiento", valor(payload, "fecha_nacimiento_" + solicitante));
    // Dirección y contacto son los del deudor (conyuge_1): mismo hogar.
    remision(c, "direccion", S_SOLICITANTE, "Dirección domiciliaria", valor(payload, "direccion_deudor"));
    remision(c, "telefono", S_SOLICITANTE, "Teléfono convencional o celular", telefonos(payload));
    remision(c, "correo", S_SOLICITANTE, "Correo electrónico", valor(payload, "correo_deudor"));
    remision(c, "clave_catastral", S_SOLICITANTE, "Código catastral del predio", valor(payload, "clave_catastral"));
    formulario(c, "sol_nacionalidad", S_SOLICITANTE, "Nacionalidad", TEXTO, List.of(), "ECUATORIANA");
    formulario(c, "sol_pasaporte", S_SOLICITANTE, "Número de pasaporte", TEXTO, List.of(), "");
    String estado = estadoCivil(valor(payload, "estado_civil"));
    c.add(
        new Campo(
            "estado_civil", S_SOLICITANTE, "Estado civil al adquirir", OPCION, ESTADOS_CIVILES, estado,
            estado.isEmpty()));

    remision(c, "cony_nombre", S_CONYUGE, "Nombres y apellidos", valor(payload, "nombre_" + conyuge));
    remision(c, "cony_cedula", S_CONYUGE, "C.I.", valor(payload, "cedula_" + conyuge));
    remision(
        c, "cony_nacimiento", S_CONYUGE, "Fecha de nacimiento", valor(payload, "fecha_nacimiento_" + conyuge));
    formulario(c, "cony_nacionalidad", S_CONYUGE, "Nacionalidad", TEXTO, List.of(), hayConyuge ? "ECUATORIANA" : "");
    formulario(c, "cony_pasaporte", S_CONYUGE, "Número de pasaporte", TEXTO, List.of(), "");

    formulario(c, "impuesto_patente", S_IMPUESTOS, "Impuesto de patente municipal", CHECK, List.of(), "");
    formulario(c, "impuesto_otros", S_IMPUESTOS, "Otros", CHECK, List.of(), "");
    formulario(
        c, "otros_catastrales", S_IMPUESTOS, "Otros códigos catastrales en Guayaquil", TEXTO, List.of(), "");

    formulario(c, "devolucion", S_DEVOLUCION, "¿Pagó el predial urbano?", SI_NO, List.of(), "");
    formulario(c, "devolucion_codigo", S_DEVOLUCION, "Código predial", TEXTO, List.of(), "");
    formulario(c, "devolucion_anios", S_DEVOLUCION, "Año(s) requerido(s)", TEXTO, List.of(), "");
    formulario(
        c, "devolucion_en", S_DEVOLUCION, "Devolución en", OPCION, List.of(NOTA_CREDITO, CUENTA), "");

    declaracion(c, "sol", S_DECL_SOL);
    declaracion(c, "cony", S_DECL_CONY);
    return c;
  }

  private static void declaracion(List<Campo> c, String p, String seccion) {
    formulario(c, p + "_ingresos", seccion, "Ingresos mensuales exceden 5 SBU", SI_NO, List.of(), "");
    formulario(c, p + "_patrimonio", seccion, "Patrimonio excede 500 SBU", SI_NO, List.of(), "");
    formulario(c, p + "_ingreso_1", seccion, "Ingreso mensual estimado (año 1)", TEXTO, List.of(), "");
    formulario(c, p + "_ingreso_2", seccion, "Ingreso mensual estimado (año 2)", TEXTO, List.of(), "");
    formulario(c, p + "_ingreso_3", seccion, "Ingreso mensual estimado (año 3)", TEXTO, List.of(), "");
    formulario(c, p + "_renta", seccion, "Declaró Impuesto a la Renta (últimos 3 períodos)", SI_NO, List.of(), "");
  }

  /** Dato de la remisión: la notaría solo lo llena si el bufete no lo trajo. */
  private static void remision(List<Campo> c, String clave, String seccion, String etiqueta, String valor) {
    c.add(new Campo(clave, seccion, etiqueta, TEXTO, List.of(), valor, valor.isEmpty()));
  }

  private static void formulario(
      List<Campo> c, String clave, String seccion, String etiqueta, String tipo, List<String> opciones,
      String porDefecto) {
    c.add(new Campo(clave, seccion, etiqueta, tipo, opciones, porDefecto, true));
  }

  /**
   * Valor final por clave: el prellenado, o lo que envió la notaría en los editables. Rechaza claves
   * desconocidas o no editables: corregir al bufete es devolver la remisión.
   */
  static Map<String, String> valores(List<Campo> campos, Map<String, String> enviados) {
    Map<String, Campo> porClave = new LinkedHashMap<>();
    campos.forEach(campo -> porClave.put(campo.clave(), campo));
    Map<String, String> envio = enviados == null ? Map.of() : enviados;
    List<String> invalidas =
        envio.keySet().stream()
            .filter(k -> !porClave.containsKey(k) || !porClave.get(k).editable())
            .toList();
    if (!invalidas.isEmpty()) {
      throw NotaryBusinessException.badRequest(
          "Campos que la notaría no puede completar: " + String.join(", ", invalidas));
    }
    Map<String, String> v = new LinkedHashMap<>();
    for (Campo campo : campos) {
      String enviado = envio.get(campo.clave());
      v.put(campo.clave(), enviado == null ? campo.valor() : limpio(enviado));
    }
    return v;
  }

  static byte[] llenar(JsonNode payload, String solicitante, LocalDate fecha, Map<String, String> enviados) {
    Map<String, String> v = valores(campos(payload, solicitante), enviados);
    try (InputStream in = new ClassPathResource(PLANTILLA).getInputStream();
        PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
      PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (PDPageContentStream cs = stream(doc, doc.getPage(0))) {
        centrado(cs, font, String.format("%02d", fecha.getDayOfMonth()), 440.7f, 734);
        centrado(cs, font, String.format("%02d", fecha.getMonthValue()), 485.7f, 734);
        centrado(cs, font, String.valueOf(fecha.getYear()), 534.5f, 734);

        texto(cs, font, v.get("sol_nombre"), X_IZQ, 692, ANCHO_IZQ);
        texto(cs, font, v.get("sol_cedula"), X_DER, 692, ANCHO_DER);
        texto(cs, font, v.get("direccion"), X_IZQ, 668, ANCHO_IZQ);
        texto(cs, font, v.get("clave_catastral"), X_DER, 668, ANCHO_DER);
        texto(cs, font, v.get("sol_nacimiento"), X_IZQ, 640, ANCHO_FECHA);
        texto(cs, font, v.get("sol_pasaporte"), X_PAS, 640, ANCHO_PAS);
        texto(cs, font, v.get("sol_nacionalidad"), X_DER, 640, ANCHO_DER);
        texto(cs, font, v.get("telefono"), X_IZQ, 617, ANCHO_IZQ);
        texto(cs, font, v.get("correo"), X_DER, 617, ANCHO_DER);

        texto(cs, font, v.get("cony_nombre"), X_IZQ, 568, ANCHO_IZQ);
        texto(cs, font, v.get("cony_cedula"), X_DER, 568, ANCHO_DER);
        texto(cs, font, v.get("cony_nacimiento"), X_IZQ, 538, ANCHO_FECHA);
        texto(cs, font, v.get("cony_pasaporte"), X_PAS, 538, ANCHO_PAS);
        texto(cs, font, v.get("cony_nacionalidad"), X_DER, 538, ANCHO_DER);

        // Predial con el código del predio que se adquiere más los otros que declare.
        String catastrales =
            Stream.of(v.get("clave_catastral"), v.get("otros_catastrales"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
        if (!catastrales.isEmpty()) {
          marca(cs, 131.3f, 498.3f);
          texto(cs, font, catastrales, 45, 474, 505);
        }
        if (SI.equals(v.get("impuesto_patente"))) {
          marca(cs, 189.3f, 440.8f);
        }
        if (SI.equals(v.get("impuesto_otros"))) {
          marca(cs, 296.7f, 440.8f);
        }

        siNo(cs, v.get("devolucion"), 66.8f, 400.5f, 67.5f, 380.5f);
        texto(cs, font, v.get("devolucion_codigo"), 148, 402, 155);
        texto(cs, font, v.get("devolucion_anios"), 395, 402, 150);
        if (NOTA_CREDITO.equals(v.get("devolucion_en"))) {
          marca(cs, 216.8f, 380.5f);
        } else if (CUENTA.equals(v.get("devolucion_en"))) {
          marca(cs, 325.5f, 379.8f);
        }

        marcarEstadoCivil(cs, v.get("estado_civil"));
        marca(cs, 127.2f, 265); // Compraventa: es el acto que se escritura.

        // Declaración: la X va sobre la raya de NO__ / SI___.
        siNo(cs, v.get("sol_ingresos"), 285, 213.5f, 262, 213.5f);
        siNo(cs, v.get("sol_patrimonio"), 153.7f, 189, 125, 189);
        siNo(cs, v.get("cony_ingresos"), 343, 201, 321, 201);
        siNo(cs, v.get("cony_patrimonio"), 456, 189, 430, 189);
        texto(cs, font, v.get("sol_ingreso_1"), 72, 128.6f, 210);
        texto(cs, font, v.get("sol_ingreso_2"), 72, 116, 210);
        texto(cs, font, v.get("sol_ingreso_3"), 72, 103.3f, 210);
        texto(cs, font, v.get("cony_ingreso_1"), 334, 128.6f, 210);
        texto(cs, font, v.get("cony_ingreso_2"), 334, 116, 210);
        texto(cs, font, v.get("cony_ingreso_3"), 334, 103.3f, 210);
      }
      try (PDPageContentStream cs = stream(doc, doc.getPage(1))) {
        siNo(cs, v.get("sol_renta"), 65.5f, 728.7f, 66.2f, 703.7f);
        siNo(cs, v.get("cony_renta"), 327.2f, 728.7f, 327.2f, 703.5f);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new UncheckedIOException("No se pudo llenar la solicitud de exoneración", ex);
    }
  }

  private static PDPageContentStream stream(PDDocument doc, PDPage page) throws IOException {
    return new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true);
  }

  /** Marca la casilla de SI o la de NO según el valor; vacío no marca nada. */
  private static void siNo(
      PDPageContentStream cs, String valor, float siX, float siY, float noX, float noY) throws IOException {
    if (SI.equals(valor)) {
      marca(cs, siX, siY);
    } else if (NO.equals(valor)) {
      marca(cs, noX, noY);
    }
  }

  /** Estado civil normalizado a una de {@link #ESTADOS_CIVILES}, o "" si no se reconoce. */
  static String estadoCivil(String estado) {
    String e = sinTildes(estado);
    if (e.startsWith("SOLTER")) {
      return ESTADOS_CIVILES.get(0);
    }
    if (e.startsWith("CASAD")) {
      return ESTADOS_CIVILES.get(1);
    }
    if (e.startsWith("DIVORCIAD")) {
      return ESTADOS_CIVILES.get(2);
    }
    if (e.startsWith("VIUD")) {
      return ESTADOS_CIVILES.get(3);
    }
    if (e.contains("UNION") || e.contains("HECHO")) {
      return ESTADOS_CIVILES.get(4);
    }
    return "";
  }

  private static void marcarEstadoCivil(PDPageContentStream cs, String estado) throws IOException {
    switch (ESTADOS_CIVILES.indexOf(estado)) {
      case 0 -> marca(cs, 114.5f, 320.7f);
      case 1 -> marca(cs, 301.2f, 320.8f);
      case 2 -> marca(cs, 463.5f, 320);
      case 3 -> marca(cs, 115.2f, 300.3f);
      case 4 -> marca(cs, 301.2f, 298.3f);
      default -> {
        // sin estado civil reconocible: la casilla queda para llenar a mano
      }
    }
  }

  private static String telefonos(JsonNode payload) {
    return Stream.of(valor(payload, "celular_comprador"), valor(payload, "telefono_deudor"))
        .filter(t -> !t.isEmpty())
        .distinct()
        .collect(Collectors.joining(" / "));
  }

  /** Cédulas y teléfonos pueden venir «número (LETRAS)»: aquí va solo el número. */
  static String valor(JsonNode payload, String campo) {
    JsonNode nodo = payload.get(campo);
    if (nodo == null || nodo.isNull() || MinutaViviendaData.isMissing(nodo.asText(""))) {
      return "";
    }
    return limpio(String.valueOf(LetrasNotariales.soloNumero(campo, nodo.asText("").trim())));
  }

  private static String limpio(String valor) {
    return valor.replaceAll("\\s+", " ").trim();
  }

  /** Reduce la letra si no cabe en la celda. */
  private static void texto(
      PDPageContentStream cs, PDFont font, String valor, float x, float y, float ancho)
      throws IOException {
    String t = codificable(font, valor == null ? "" : valor);
    if (t.isEmpty()) {
      return;
    }
    float anchoTexto = font.getStringWidth(t) / 1000 * TAM;
    float tam = anchoTexto > ancho ? TAM * ancho / anchoTexto : TAM;
    cs.beginText();
    cs.setFont(font, tam);
    cs.newLineAtOffset(x, y);
    cs.showText(t);
    cs.endText();
  }

  private static void centrado(PDPageContentStream cs, PDFont font, String valor, float centro, float y)
      throws IOException {
    float ancho = font.getStringWidth(valor) / 1000 * TAM;
    texto(cs, font, valor, centro - ancho / 2, y, ancho);
  }

  /** X dentro de la casilla (centro en coordenadas PDF). */
  private static void marca(PDPageContentStream cs, float cx, float cy) throws IOException {
    float r = 3f;
    cs.setLineWidth(1.2f);
    cs.moveTo(cx - r, cy - r);
    cs.lineTo(cx + r, cy + r);
    cs.moveTo(cx - r, cy + r);
    cs.lineTo(cx + r, cy - r);
    cs.stroke();
  }

  /** Helvetica estándar solo cubre WinAnsi: se omite lo que no puede dibujar. */
  private static String codificable(PDFont font, String valor) {
    StringBuilder sb = new StringBuilder();
    valor.codePoints()
        .forEach(
            cp -> {
              String c = new String(Character.toChars(cp));
              try {
                font.encode(c);
                sb.append(c);
              } catch (IOException | IllegalArgumentException ex) {
                // carácter fuera de WinAnsi
              }
            });
    return sb.toString();
  }

  private static String sinTildes(String valor) {
    return Normalizer.normalize(valor == null ? "" : valor, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toUpperCase(Locale.ROOT)
        .trim();
  }
}
