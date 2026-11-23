package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class BandejaExcelTest {

  @Test
  void escribeLaFilaConElOrdenDeLaBandeja() throws Exception {
    byte[] bytes =
        BandejaExcel.escribir(
            java.util.List.of(
                new BandejaExcel.Fila(
                    "LEX-1", "INS-9", "Ana", "VIV_TERMINADA_IND", "Minutas", "En estudio", "23 nov 2026")));
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      var row = book.getSheetAt(0).getRow(1);
      assertEquals("LEX-1", row.getCell(0).getStringCellValue());
      assertEquals("INS-9", row.getCell(1).getStringCellValue());
      assertEquals("Ana", row.getCell(2).getStringCellValue());
      assertEquals("VIV_TERMINADA_IND", row.getCell(3).getStringCellValue());
      assertEquals("Minutas", row.getCell(4).getStringCellValue());
      assertEquals("En estudio", row.getCell(5).getStringCellValue());
      assertEquals("23 nov 2026", row.getCell(6).getStringCellValue());
    }
  }
}
