package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class BandejaExcelTest {

  @Test
  void escribeLasColumnasDeLaBandejaConLaFechaComoFechaDeExcel() throws Exception {
    LocalDateTime cuando = LocalDateTime.of(2026, 11, 23, 15, 40);
    byte[] bytes =
        BandejaExcel.escribir(
            List.of(
                new BandejaExcel.Fila(
                    "LEX-1",
                    "INS-9",
                    "Cliente sintético",
                    "Vivienda terminada individual",
                    "Minutas",
                    "Entrega a notaría",
                    "Enviado a la notaría.",
                    cuando,
                    "Responsable sintético"),
                new BandejaExcel.Fila("LEX-2", null, "Otro", "", "—", null, null, null, null)));
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      var sheet = book.getSheetAt(0);
      var head = sheet.getRow(0);
      for (int i = 0; i < BandejaExcel.ENCABEZADOS.length; i++) {
        assertEquals(BandejaExcel.ENCABEZADOS[i], head.getCell(i).getStringCellValue());
      }
      var row = sheet.getRow(1);
      assertEquals("LEX-1", row.getCell(0).getStringCellValue());
      assertEquals("INS-9", row.getCell(1).getStringCellValue());
      assertEquals("Cliente sintético", row.getCell(2).getStringCellValue());
      assertEquals("Vivienda terminada individual", row.getCell(3).getStringCellValue());
      assertEquals("Minutas", row.getCell(4).getStringCellValue());
      assertEquals("Entrega a notaría", row.getCell(5).getStringCellValue());
      assertEquals("Enviado a la notaría.", row.getCell(6).getStringCellValue());
      var fecha = row.getCell(BandejaExcel.COL_FECHA);
      assertEquals(CellType.NUMERIC, fecha.getCellType());
      assertTrue(DateUtil.isCellDateFormatted(fecha));
      assertEquals(cuando, fecha.getLocalDateTimeCellValue());
      assertEquals("Responsable sintético", row.getCell(8).getStringCellValue());

      // Sin estatus: celdas vacías, no «null» ni «—».
      var vacia = sheet.getRow(2);
      assertEquals("", vacia.getCell(1).getStringCellValue());
      assertEquals("", vacia.getCell(4).getStringCellValue());
      assertEquals(CellType.BLANK, vacia.getCell(BandejaExcel.COL_FECHA).getCellType());
      assertTrue(sheet.getCTWorksheet().isSetAutoFilter());
    }
  }

  @Test
  void nombreDelArchivoLlevaLaFecha() {
    assertEquals(
        "bandeja-escrituracion-2026-11-24.xlsx", BandejaExcel.nombreArchivo(LocalDate.of(2026, 11, 24)));
  }
}
