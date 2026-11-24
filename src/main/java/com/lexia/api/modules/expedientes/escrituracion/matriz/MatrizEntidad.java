package com.lexia.api.modules.expedientes.escrituracion.matriz;

import com.lexia.api.common.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Matriz que envía la entidad: se ubican por encabezado (no por letra) la columna del número de
 * instancia, la de etapa («ETAPA 11-11-2026») y la de observación. Solo se escriben esas dos últimas
 * en las filas indicadas; el resto del libro (hojas, formatos, fórmulas, filtros, anchos) no se toca.
 * Todo en memoria: el archivo no se guarda.
 */
public final class MatrizEntidad {

  public static final long MAX_BYTES = 5L * 1024 * 1024;
  /** El encabezado puede no estar en la fila 1 (títulos arriba). */
  private static final int FILAS_BUSQUEDA_ENCABEZADO = 30;
  private static final long MAX_CONTENT_TYPES = 1024 * 1024;

  private MatrizEntidad() {}

  /** Hoja, fila de encabezado (0-based) y columnas ubicadas. */
  public record Columnas(int hoja, int filaEncabezado, int instancia, int etapa, int observacion) {}

  /**
   * @param fila índice de fila 0-based (en Excel es {@code fila + 1}).
   * @param numero número de instancia normalizado.
   */
  public record FilaMatriz(int fila, String numero, boolean observacionConTexto) {}

  /** Lo que se escribe en una fila. */
  public record Escritura(int fila, String etapa, String observacion) {}

  /**
   * Solo .xlsx sin macros, validado por contenido: firma ZIP, tipo de libro en
   * [Content_Types].xml y sin vbaProject.bin. Un .xls (OLE2) o un .xlsm se rechazan con 400.
   */
  public static void validar(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw ApiException.badRequest("Adjunta la matriz de la entidad (.xlsx).");
    }
    if (bytes.length > MAX_BYTES) {
      throw ApiException.badRequest("La matriz supera el máximo de 5 MB.");
    }
    if (empiezaCon(bytes, 0xD0, 0xCF, 0x11, 0xE0)) {
      throw ApiException.badRequest("La matriz es un .xls (Excel 97-2003). Guárdala como .xlsx y súbela de nuevo.");
    }
    if (!empiezaCon(bytes, 'P', 'K', 3, 4)) {
      throw ApiException.badRequest("El archivo no es un Excel .xlsx.");
    }
    String contentTypes = null;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        String nombre = e.getName().toLowerCase(Locale.ROOT);
        if (nombre.contains("vbaproject") || nombre.endsWith(".bin") && nombre.contains("vba")) {
          throw ApiException.badRequest("La matriz trae macros. Súbela como .xlsx sin macros.");
        }
        if ("[content_types].xml".equals(nombre)) {
          byte[] xml = zip.readNBytes((int) MAX_CONTENT_TYPES);
          contentTypes = new String(xml, StandardCharsets.UTF_8);
        }
      }
    } catch (IOException ex) {
      throw ApiException.badRequest("No se pudo leer la matriz: el archivo .xlsx está dañado.");
    }
    if (contentTypes == null || !contentTypes.contains("spreadsheetml.sheet.main+xml")) {
      throw contentTypes != null && contentTypes.contains("macroEnabled")
          ? ApiException.badRequest("La matriz trae macros. Súbela como .xlsx sin macros.")
          : ApiException.badRequest("El archivo no es un libro de Excel .xlsx.");
    }
  }

  public static XSSFWorkbook abrir(byte[] bytes) {
    validar(bytes);
    try {
      return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    } catch (IOException | RuntimeException ex) {
      throw ApiException.badRequest("No se pudo abrir la matriz .xlsx.");
    }
  }

  /** Ubica el encabezado en la primera hoja que tenga la columna del número de instancia. */
  public static Columnas ubicar(XSSFWorkbook book) {
    for (int h = 0; h < book.getNumberOfSheets(); h++) {
      Sheet sheet = book.getSheetAt(h);
      int ultima = Math.min(sheet.getLastRowNum(), FILAS_BUSQUEDA_ENCABEZADO);
      for (int r = sheet.getFirstRowNum(); r <= ultima; r++) {
        Row row = sheet.getRow(r);
        if (row == null) {
          continue;
        }
        int instancia = -1;
        int etapa = -1;
        int observacion = -1;
        for (Cell cell : row) {
          String h1 = normalizar(texto(cell));
          if (h1.isEmpty()) {
            continue;
          }
          if ("NUMERO INSTANCIA PROCESO".equals(h1) || instancia < 0 && h1.contains("INSTANCIA")) {
            instancia = cell.getColumnIndex();
          } else if (etapa < 0 && h1.startsWith("ETAPA")) {
            etapa = cell.getColumnIndex();
          } else if (observacion < 0 && h1.contains("OBSERVACION")) {
            observacion = cell.getColumnIndex();
          }
        }
        if (instancia >= 0) {
          if (etapa < 0) {
            throw ApiException.badRequest("La matriz no tiene la columna ETAPA en el encabezado.");
          }
          if (observacion < 0) {
            throw ApiException.badRequest("La matriz no tiene la columna OBSERVACION en el encabezado.");
          }
          return new Columnas(h, r, instancia, etapa, observacion);
        }
      }
    }
    throw ApiException.badRequest(
        "La matriz no tiene la columna NUMERO INSTANCIA PROCESO en el encabezado.");
  }

  /** Filas de datos con número de instancia (las vacías se ignoran). */
  public static List<FilaMatriz> filas(XSSFWorkbook book, Columnas c) {
    Sheet sheet = book.getSheetAt(c.hoja());
    List<FilaMatriz> out = new ArrayList<>();
    for (int r = c.filaEncabezado() + 1; r <= sheet.getLastRowNum(); r++) {
      Row row = sheet.getRow(r);
      if (row == null) {
        continue;
      }
      String numero = numeroInstancia(row.getCell(c.instancia()));
      if (numero.isEmpty()) {
        continue;
      }
      out.add(new FilaMatriz(r, numero, !texto(row.getCell(c.observacion())).isBlank()));
    }
    return out;
  }

  /** Escribe etapa y observación en las filas dadas y devuelve el libro completo. */
  public static byte[] escribir(XSSFWorkbook book, Columnas c, List<Escritura> escrituras) {
    Sheet sheet = book.getSheetAt(c.hoja());
    for (Escritura e : escrituras) {
      Row row = sheet.getRow(e.fila());
      poner(sheet, row, c.etapa(), e.etapa());
      poner(sheet, row, c.observacion(), e.observacion());
    }
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      book.write(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("No se pudo escribir la matriz.");
    }
  }

  /** Celda existente: solo cambia el valor (conserva su formato). Nueva: toma el formato de la columna. */
  private static void poner(Sheet sheet, Row row, int col, String valor) {
    Cell cell = row.getCell(col);
    if (cell == null) {
      cell = row.createCell(col);
      CellStyle estilo = sheet.getColumnStyle(col);
      if (estilo != null) {
        cell.setCellStyle(estilo);
      }
    }
    cell.setCellValue(valor == null ? "" : valor);
  }

  /** Texto sin espacios; un número sin decimales ni notación científica (12345678.0 → 12345678). */
  static String numeroInstancia(Cell cell) {
    if (cell == null) {
      return "";
    }
    CellType tipo = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    if (tipo == CellType.NUMERIC) {
      return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    }
    return sinEspacios(texto(cell));
  }

  public static String sinEspacios(String valor) {
    return valor == null ? "" : valor.replaceAll("[\\s\\u00A0]+", "");
  }

  private static String texto(Cell cell) {
    if (cell == null) {
      return "";
    }
    CellType tipo = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    return switch (tipo) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      default -> "";
    };
  }

  /** Mayúsculas, sin tildes ni signos, espacios simples: «Número  Instancia» → «NUMERO INSTANCIA». */
  static String normalizar(String valor) {
    String plano = Normalizer.normalize(valor == null ? "" : valor, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return plano.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
  }

  private static boolean empiezaCon(byte[] bytes, int... firma) {
    if (bytes.length < firma.length) {
      return false;
    }
    for (int i = 0; i < firma.length; i++) {
      if ((bytes[i] & 0xFF) != firma[i]) {
        return false;
      }
    }
    return true;
  }
}
