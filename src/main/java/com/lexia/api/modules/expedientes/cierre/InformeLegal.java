package com.lexia.api.modules.expedientes.cierre;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;

/**
 * Informe legal final: la plantilla de la abogada ({@code ADICIONALES/informe_legal.xlsx}) con las
 * celdas variables llenas. Solo se cambia el valor de cada celda; estilo, combinaciones e imagen
 * quedan como en la plantilla.
 */
public final class InformeLegal {

  public static final String RECURSO = "templates/escrituracion/ADICIONALES/informe_legal.xlsx";

  /**
   * @param conVendedores false en sustitución de hipoteca (no hay compraventa).
   */
  public record Datos(
      String nip,
      String propietarios,
      String tipoContrato,
      String descripcionInmueble,
      String parroquia,
      String canton,
      String provincia,
      String cuantia,
      String notaria,
      String fechaEscritura,
      String fechaInscripcion,
      String vendedores,
      String observaciones,
      String ciudad,
      String fechaEmision,
      String abogado,
      String registro,
      boolean conVendedores) {}

  private InformeLegal() {}

  /** Datos sin los cuales no se genera (etiqueta legible, en orden del informe). */
  public static List<String> faltantes(Datos d) {
    List<String> out = new ArrayList<>();
    falta(out, d.nip(), "NIP");
    falta(out, d.propietarios(), "Propietarios / deudores (compradores)");
    falta(out, d.tipoContrato(), "Tipo de contrato (producto)");
    falta(out, d.descripcionInmueble(), "Descripción del inmueble");
    falta(out, d.parroquia(), "Parroquia");
    falta(out, d.canton(), "Cantón");
    falta(out, d.provincia(), "Provincia");
    falta(out, d.cuantia(), "Cuantía (monto del préstamo)");
    falta(out, d.notaria(), "Notaría de la escritura nueva");
    falta(out, d.fechaEscritura(), "Fecha de la escritura nueva");
    falta(out, d.fechaInscripcion(), "Fecha de inscripción de la escritura nueva");
    if (d.conVendedores()) {
      falta(out, d.vendedores(), "Vendedores");
    }
    falta(out, d.fechaEmision(), "Fecha de emisión del informe");
    return out;
  }

  public static byte[] escribir(Datos d) {
    try (InputStream in = new ClassPathResource(RECURSO).getInputStream();
        XSSFWorkbook book = new XSSFWorkbook(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XSSFSheet sheet = book.getSheetAt(0);
      poner(sheet, "C4", "NIP: " + v(d.nip()));
      poner(sheet, "C5", "Nombres y Apellidos Propietario del Bien: " + v(d.propietarios()));
      poner(sheet, "C9", v(d.tipoContrato()));
      poner(sheet, "C14", v(d.descripcionInmueble()));
      poner(sheet, "C16", "Parroquia: " + v(d.parroquia()));
      poner(sheet, "C17", "Cantón: " + v(d.canton()));
      poner(sheet, "C18", "Provincia: " + v(d.provincia()));
      poner(sheet, "E21", v(d.cuantia()));
      poner(sheet, "F24", v(d.notaria()));
      poner(sheet, "F25", v(d.fechaEscritura()));
      poner(sheet, "F26", v(d.fechaInscripcion()));
      poner(sheet, "E30", v(d.propietarios()));
      poner(sheet, "E32", d.conVendedores() ? v(d.vendedores()) : "");
      poner(sheet, "E34", "BIESS");
      poner(sheet, "C39", v(d.observaciones()));
      String lugar = v(d.ciudad());
      poner(sheet, "C45", lugar.isEmpty() ? v(d.fechaEmision()) : lugar + ", " + v(d.fechaEmision()));
      String elaborado = "Elaborado por:";
      if (!v(d.abogado()).isEmpty()) {
        elaborado += " Ab. " + v(d.abogado());
        if (!v(d.registro()).isEmpty()) {
          elaborado += "\nReg.Prof. " + v(d.registro());
        }
      }
      poner(sheet, "C53", elaborado);
      book.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("No se pudo armar el informe legal.", e);
    }
  }

  private static void poner(XSSFSheet sheet, String ref, String valor) {
    CellReference c = new CellReference(ref);
    Row row = sheet.getRow(c.getRow());
    if (row == null) {
      row = sheet.createRow(c.getRow());
    }
    Cell cell = row.getCell(c.getCol());
    if (cell == null) {
      cell = row.createCell(c.getCol());
    }
    cell.setCellValue(valor);
  }

  private static void falta(List<String> out, String valor, String etiqueta) {
    if (v(valor).isEmpty()) {
      out.add(etiqueta);
    }
  }

  private static String v(String s) {
    return s == null ? "" : s.trim();
  }
}
