package com.lexia.api.modules.expedientes.caso;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Excel de la bandeja. El orden de columnas queda fijo hasta que llegue la matriz de la entidad. */
public final class BandejaExcel {

  public record Fila(
      String codigo,
      String numeroInstancia,
      String cliente,
      String producto,
      String etapa,
      String estatus,
      String estatusEn) {}

  private BandejaExcel() {}

  public static byte[] escribir(List<Fila> filas) {
    try (XSSFWorkbook book = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("Escrituracion");
      String[] headers = {
        "Codigo",
        "Numero de instancia",
        "Cliente",
        "Producto",
        "Etapa",
        "Ultimo estatus",
        "Fecha del estatus"
      };
      Row head = sheet.createRow(0);
      for (int i = 0; i < headers.length; i++) {
        head.createCell(i).setCellValue(headers[i]);
      }
      int n = 1;
      for (Fila fila : filas) {
        Row row = sheet.createRow(n++);
        row.createCell(0).setCellValue(vacio(fila.codigo()));
        row.createCell(1).setCellValue(vacio(fila.numeroInstancia()));
        row.createCell(2).setCellValue(vacio(fila.cliente()));
        row.createCell(3).setCellValue(vacio(fila.producto()));
        row.createCell(4).setCellValue(vacio(fila.etapa()));
        row.createCell(5).setCellValue(vacio(fila.estatus()));
        row.createCell(6).setCellValue(vacio(fila.estatusEn()));
      }
      book.write(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("No se pudo armar el Excel de la bandeja.");
    }
  }

  private static String vacio(String value) {
    return value == null ? "" : value;
  }
}
