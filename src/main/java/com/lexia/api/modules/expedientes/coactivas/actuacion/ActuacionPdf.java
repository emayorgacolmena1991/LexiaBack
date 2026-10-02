package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** PDF mínimo con los datos que entrega el expediente. No sustituye la plantilla Word. */
public final class ActuacionPdf {

  private ActuacionPdf() {}

  public static byte[] generar(String titulo, List<String> lineas) {
    StringBuilder text = new StringBuilder();
    text.append("BT\n/F1 14 Tf\n72 740 Td\n(").append(escapar(titulo)).append(") Tj\n/F1 11 Tf\n");
    int y = 710;
    for (String linea : lineas) {
      text.append("1 0 0 1 72 ").append(y).append(" Tm\n(").append(escapar(linea)).append(") Tj\n");
      y -= 18;
    }
    text.append("ET\n");
    byte[] stream = text.toString().getBytes(StandardCharsets.ISO_8859_1);
    byte[] obj1 = ascii("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
    byte[] obj2 = ascii("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n");
    byte[] obj3 =
        ascii(
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj\n");
    byte[] obj4Head = ascii("4 0 obj << /Length " + stream.length + " >> stream\n");
    byte[] obj4Tail = ascii("endstream\nendobj\n");
    byte[] obj5 = ascii("5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n");
    byte[] header = ascii("%PDF-1.4\n");
    int[] offsets = new int[6];
    int cursor = header.length;
    offsets[1] = cursor;
    cursor += obj1.length;
    offsets[2] = cursor;
    cursor += obj2.length;
    offsets[3] = cursor;
    cursor += obj3.length;
    offsets[4] = cursor;
    cursor += obj4Head.length + stream.length + obj4Tail.length;
    offsets[5] = cursor;
    cursor += obj5.length;
    StringBuilder xref = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
    for (int i = 1; i <= 5; i++) {
      xref.append(String.format("%010d 00000 n \n", offsets[i]));
    }
    xref.append("trailer << /Size 6 /Root 1 0 R >>\nstartxref\n").append(cursor).append("\n%%EOF\n");
    byte[] xrefBytes = ascii(xref.toString());
    byte[] pdf = new byte[cursor + xrefBytes.length];
    int pos = 0;
    pos = copy(pdf, pos, header);
    pos = copy(pdf, pos, obj1);
    pos = copy(pdf, pos, obj2);
    pos = copy(pdf, pos, obj3);
    pos = copy(pdf, pos, obj4Head);
    pos = copy(pdf, pos, stream);
    pos = copy(pdf, pos, obj4Tail);
    pos = copy(pdf, pos, obj5);
    copy(pdf, pos, xrefBytes);
    return pdf;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static int copy(byte[] target, int pos, byte[] chunk) {
    System.arraycopy(chunk, 0, target, pos, chunk.length);
    return pos + chunk.length;
  }

  static String escapar(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == '(' || c == ')') {
        out.append('\\');
      }
      if (c < 32 || c > 255) {
        out.append('?');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  public static List<String> lineas(String... valores) {
    List<String> lineas = new ArrayList<>();
    for (String valor : valores) {
      if (valor != null && !valor.isBlank()) {
        lineas.add(valor);
      }
    }
    return lineas;
  }
}
