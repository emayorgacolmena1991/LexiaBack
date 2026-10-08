package com.lexia.api.modules.expedientes.coactivas.embargo;

import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.Datos;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataValidation;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataValidations;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STDataValidationType;

/**
 * Rellena una copia en memoria de {@code DATA 2.xlsx}: encabezado en la fila 1, datos desde la 2.
 * El archivo de resources solo se lee.
 */
final class CoactivaEmbargoExcel {

  static final String PLANTILLA = "templates/embargo/DATA 2.xlsx";
  static final String HOJA = "UEC MANTA";
  static final int COLUMNAS = 11;
  static final int COL_TITULAR = 5;
  static final int COL_VALOR = 6;
  static final int COL_FECHA = 7;
  /** La plantilla trae bordes hasta la fila 192; desde la 193 se copia el estilo de la fila 3. */
  static final int PRIMERA_FILA_SIN_ESTILO = 192;
  private static final int FILA_MODELO = 2;

  private CoactivaEmbargoExcel() {}

  static byte[] generar(InputStream plantilla, List<Datos> filas) throws IOException {
    try (XSSFWorkbook wb = new XSSFWorkbook(plantilla);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XSSFSheet hoja = wb.getSheet(HOJA);
      if (hoja == null) {
        throw new IllegalStateException("La plantilla no tiene la hoja " + HOJA);
      }
      quitarValidacionesDesfasadas(hoja);
      Row modelo = hoja.getRow(FILA_MODELO);
      CellStyle fecha = estiloFecha(wb, modelo);
      for (int i = 0; i < filas.size(); i++) {
        int r = 1 + i;
        Row row = hoja.getRow(r) != null ? hoja.getRow(r) : hoja.createRow(r);
        if (r >= PRIMERA_FILA_SIN_ESTILO && modelo != null) {
          row.setHeight(modelo.getHeight());
        }
        Object[] valores = valores(filas.get(i));
        for (int c = 0; c < COLUMNAS; c++) {
          Cell cell = row.getCell(c) != null ? row.getCell(c) : row.createCell(c);
          if (c == COL_FECHA) {
            cell.setCellStyle(fecha);
          } else if (r >= PRIMERA_FILA_SIN_ESTILO && modelo != null && modelo.getCell(c) != null) {
            cell.setCellStyle(modelo.getCell(c).getCellStyle());
          }
          escribir(cell, valores[c]);
        }
      }
      wb.write(out);
      return out.toByteArray();
    }
  }

  private static Object[] valores(Datos d) {
    return new Object[] {
      d.juzgado(),
      d.oficinaOrigenCredito(),
      d.operacion(),
      d.numeroJuicio(),
      d.nombreCoactivado(),
      d.nombreTitularOperacion(),
      d.valorTransferido(),
      d.fechaProceso(),
      d.nombreDerSac(),
      d.numeroOficioRespuesta(),
      d.numeroDocumento()
    };
  }

  /** Texto siempre como String: operación, juicio, oficio y documento conservan ceros a la izquierda. */
  private static void escribir(Cell cell, Object valor) {
    if (valor == null) {
      cell.setBlank();
    } else if (valor instanceof BigDecimal n) {
      cell.setCellValue(n.doubleValue());
    } else if (valor instanceof LocalDate f) {
      cell.setCellValue(f);
    } else {
      cell.setCellValue(valor.toString());
    }
  }

  /** H2 es texto y desde H4 es General: la fecha usa siempre el estilo D/M/YYYY de H3. */
  private static CellStyle estiloFecha(XSSFWorkbook wb, Row modelo) {
    Cell h3 = modelo == null ? null : modelo.getCell(COL_FECHA);
    if (h3 != null && DateUtil.isADateFormat(h3.getCellStyle().getDataFormat(), h3.getCellStyle().getDataFormatString())) {
      return h3.getCellStyle();
    }
    CellStyle estilo = wb.createCellStyle();
    if (h3 != null) {
      estilo.cloneStyleFrom(h3.getCellStyle());
    }
    estilo.setDataFormat(wb.createDataFormat().getFormat("d/m/yyyy"));
    return estilo;
  }

  /**
   * La plantilla trae validaciones corridas de columna: decimal sobre FECHA DE PROCESO (H2) y
   * {@code LTE(LEN(F2),20)} sobre el nombre del titular. Se quitan solo esas de la copia; el resto
   * (p. ej. máximo 250 en NOMBRE COACTIVADO) se conserva.
   */
  static void quitarValidacionesDesfasadas(XSSFSheet hoja) {
    CTDataValidations dvs = hoja.getCTWorksheet().getDataValidations();
    if (dvs == null) {
      return;
    }
    for (int i = dvs.sizeOfDataValidationArray() - 1; i >= 0; i--) {
      if (desfasada(dvs.getDataValidationArray(i))) {
        dvs.removeDataValidation(i);
      }
    }
    if (dvs.sizeOfDataValidationArray() == 0) {
      hoja.getCTWorksheet().unsetDataValidations();
    } else {
      dvs.setCount(dvs.sizeOfDataValidationArray());
    }
  }

  private static boolean desfasada(CTDataValidation dv) {
    STDataValidationType.Enum tipo = dv.getType();
    boolean numerica = tipo == STDataValidationType.DECIMAL || tipo == STDataValidationType.WHOLE;
    boolean longitud =
        tipo == STDataValidationType.TEXT_LENGTH
            || (tipo == STDataValidationType.CUSTOM
                && dv.getFormula1() != null
                && dv.getFormula1().toUpperCase(Locale.ROOT).contains("LEN("));
    for (Object ref : dv.getSqref()) {
      for (String rango : ref.toString().split("\\s+")) {
        CellRangeAddress area = CellRangeAddress.valueOf(rango);
        for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
          if ((numerica && c != COL_VALOR) || (longitud && c == COL_TITULAR)) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
