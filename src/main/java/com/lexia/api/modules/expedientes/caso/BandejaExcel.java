package com.lexia.api.modules.expedientes.caso;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Excel de la bandeja de escrituración: las filas que la grilla muestra con sus filtros, en su
 * orden. Columnas fijas hasta que llegue la matriz de la abogada (entonces se ajustan aquí).
 */
public final class BandejaExcel {

  static final String[] ENCABEZADOS = {
    "Código",
    "Número de instancia",
    "Cliente",
    "Producto",
    "Etapa",
    "Último estatus: etapa",
    "Último estatus: texto",
    "Fecha del estatus",
    "Responsable"
  };
  private static final int[] ANCHOS = {16, 22, 32, 28, 26, 30, 60, 18, 24};
  static final int COL_FECHA = 7;

  /**
   * @param estatusFecha fecha y hora local de Guayaquil; va como fecha de Excel, no como texto.
   */
  public record Fila(
      String codigo,
      String numeroInstancia,
      String cliente,
      String producto,
      String etapa,
      String estatusEtapa,
      String estatusTexto,
      LocalDateTime estatusFecha,
      String responsable) {}

  private BandejaExcel() {}

  /** {@code bandeja-escrituracion-2026-11-24.xlsx}. */
  public static String nombreArchivo(LocalDate hoy) {
    return "bandeja-escrituracion-" + hoy + ".xlsx";
  }

  public static byte[] escribir(List<Fila> filas) {
    try (XSSFWorkbook book = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XSSFSheet sheet = book.createSheet("Escrituración");
      CellStyle encabezado = book.createCellStyle();
      Font negrita = book.createFont();
      negrita.setBold(true);
      encabezado.setFont(negrita);
      CellStyle fecha = book.createCellStyle();
      fecha.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
      CellStyle texto = book.createCellStyle();
      texto.setWrapText(true);

      Row head = sheet.createRow(0);
      for (int i = 0; i < ENCABEZADOS.length; i++) {
        Cell cell = head.createCell(i);
        cell.setCellValue(ENCABEZADOS[i]);
        cell.setCellStyle(encabezado);
        sheet.setColumnWidth(i, ANCHOS[i] * 256);
      }
      int n = 1;
      for (Fila fila : filas) {
        Row row = sheet.createRow(n++);
        row.createCell(0).setCellValue(vacio(fila.codigo()));
        row.createCell(1).setCellValue(vacio(fila.numeroInstancia()));
        row.createCell(2).setCellValue(vacio(fila.cliente()));
        row.createCell(3).setCellValue(vacio(fila.producto()));
        row.createCell(4).setCellValue(vacio(fila.etapa()));
        row.createCell(5).setCellValue(vacio(fila.estatusEtapa()));
        Cell estatus = row.createCell(6);
        estatus.setCellValue(vacio(fila.estatusTexto()));
        estatus.setCellStyle(texto);
        Cell cuando = row.createCell(COL_FECHA);
        if (fila.estatusFecha() != null) {
          cuando.setCellValue(fila.estatusFecha());
          cuando.setCellStyle(fecha);
        }
        row.createCell(8).setCellValue(vacio(fila.responsable()));
      }
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, n - 1), 0, ENCABEZADOS.length - 1));
      book.write(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("No se pudo armar el Excel de la bandeja.");
    }
  }

  private static String vacio(String value) {
    return value == null || "—".equals(value) ? "" : value;
  }
}
