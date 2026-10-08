package com.lexia.api.modules.expedientes.coactivas.embargo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.Datos;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint.ValidationType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CoactivaEmbargoExcelTest {

  private static final ZoneId GYE = ZoneId.of("America/Guayaquil");

  private static Instant gye(String local) {
    return ZonedDateTime.of(java.time.LocalDateTime.parse(local), GYE).toInstant();
  }

  @Test
  void corteSemanalJueves12Guayaquil() {
    // 2026-10-07 es miércoles; 2026-10-08 jueves.
    assertEquals(gye("2026-10-08T12:00"), CoactivaEmbargoService.corteNuevoLote(gye("2026-10-07T10:15"), null, GYE));
    assertEquals(gye("2026-10-08T12:00"), CoactivaEmbargoService.corteNuevoLote(gye("2026-10-08T11:00"), null, GYE));
    assertEquals(gye("2026-10-15T12:00"), CoactivaEmbargoService.corteNuevoLote(gye("2026-10-08T12:00"), null, GYE));
    // Entregado el jueves 11:00, antes de su corte: el lote nuevo corta el jueves siguiente.
    assertEquals(
        gye("2026-10-15T12:00"),
        CoactivaEmbargoService.corteNuevoLote(gye("2026-10-08T11:00"), gye("2026-10-08T12:00"), GYE));
    // Entregado tarde (viernes) con corte vencido: el próximo jueves posterior a ahora.
    assertEquals(
        gye("2026-10-15T12:00"),
        CoactivaEmbargoService.corteNuevoLote(gye("2026-10-09T09:00"), gye("2026-10-08T12:00"), GYE));
  }

  @Test
  void completoExigeLasOnceColumnas() {
    Datos lleno =
        new Datos(
            "UEC MANTA", "PORTOVIEJO", "0012345", "13001-2024-00012", "PEREZ JUAN", "PEREZ JUAN",
            new BigDecimal("1.00"), LocalDate.of(2026, 10, 7), "ANA", "0007", "000123");
    assertTrue(CoactivaEmbargoService.completo(lleno));
    assertFalse(
        CoactivaEmbargoService.completo(
            new Datos(
                "UEC MANTA", "PORTOVIEJO", "0012345", "13001-2024-00012", "PEREZ JUAN", "PEREZ JUAN",
                null, LocalDate.of(2026, 10, 7), "ANA", "0007", "000123")));
    assertFalse(
        CoactivaEmbargoService.completo(
            new Datos(
                "UEC MANTA", "PORTOVIEJO", "0012345", "13001-2024-00012", "PEREZ JUAN", "PEREZ JUAN",
                new BigDecimal("1.00"), LocalDate.of(2026, 10, 7), "ANA", "0007", "  ")));
  }

  @Test
  void rellenaCopiaDeLaPlantillaSinTocarElOriginal() throws Exception {
    ClassPathResource recurso = new ClassPathResource(CoactivaEmbargoExcel.PLANTILLA);
    byte[] original;
    try (InputStream in = recurso.getInputStream()) {
      original = in.readAllBytes();
    }
    List<Datos> filas = new java.util.ArrayList<>();
    filas.add(
        new Datos(
            "UEC MANTA", "PORTOVIEJO", "0012345", "13001-2024-00012", "PEREZ JUAN", "PEREZ JUAN",
            new BigDecimal("1234.50"), LocalDate.of(2026, 10, 7), "ANA DELEGADA", "0007", "000123"));
    for (int i = 0; i < 200; i++) {
      filas.add(new Datos(null, null, null, "J-" + i, null, null, null, null, null, null, null));
    }

    try (XSSFWorkbook plantilla = new XSSFWorkbook(new ByteArrayInputStream(original))) {
      XSSFSheet hoja = plantilla.getSheet(CoactivaEmbargoExcel.HOJA);
      assertEquals(2, desfasadas(hoja), "la plantilla trae decimal en H y largo 20 en F");
      assertTrue(hoja.getDataValidations().size() > 2, "conserva las validaciones correctas");
    }

    byte[] xlsx = CoactivaEmbargoExcel.generar(new ByteArrayInputStream(original), filas);

    try (InputStream in = recurso.getInputStream()) {
      assertArrayEquals(original, in.readAllBytes());
    }
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
      XSSFSheet hoja = wb.getSheet(CoactivaEmbargoExcel.HOJA);
      Row encabezado = hoja.getRow(0);
      assertEquals("JUZGADO", encabezado.getCell(0).getStringCellValue().trim());
      assertEquals("N° DOCUMENTO", encabezado.getCell(10).getStringCellValue().trim());

      Row r = hoja.getRow(1);
      assertEquals("0012345", r.getCell(2).getStringCellValue());
      assertEquals("0007", r.getCell(9).getStringCellValue());
      assertEquals("000123", r.getCell(10).getStringCellValue());
      assertEquals(CellType.NUMERIC, r.getCell(6).getCellType());
      assertEquals(1234.5, r.getCell(6).getNumericCellValue(), 0.0001);
      assertTrue(DateUtil.isCellDateFormatted(r.getCell(7)));
      assertEquals(LocalDate.of(2026, 10, 7), r.getCell(7).getLocalDateTimeCellValue().toLocalDate());

      // Fila 193 (índice 192) sin borde en la plantilla: copia el estilo de la fila 3.
      Row fila193 = hoja.getRow(CoactivaEmbargoExcel.PRIMERA_FILA_SIN_ESTILO);
      assertEquals("J-" + (CoactivaEmbargoExcel.PRIMERA_FILA_SIN_ESTILO - 2), fila193.getCell(3).getStringCellValue());
      assertEquals(
          hoja.getRow(2).getCell(4).getCellStyle().getIndex(), fila193.getCell(4).getCellStyle().getIndex());

      assertEquals(0, desfasadas(hoja));
      assertFalse(hoja.getDataValidations().isEmpty(), "las validaciones correctas siguen");
    }
  }

  private static int desfasadas(XSSFSheet hoja) {
    int n = 0;
    for (DataValidation dv : hoja.getDataValidations()) {
      int t = dv.getValidationConstraint().getValidationType();
      String f1 = dv.getValidationConstraint().getFormula1();
      boolean largo =
          t == ValidationType.TEXT_LENGTH || (t == ValidationType.FORMULA && f1 != null && f1.contains("LEN("));
      for (CellRangeAddress area : dv.getRegions().getCellRangeAddresses()) {
        boolean cubreFecha = area.getFirstColumn() <= 7 && area.getLastColumn() >= 7;
        boolean cubreTitular = area.getFirstColumn() <= 5 && area.getLastColumn() >= 5;
        if ((cubreFecha && t == ValidationType.DECIMAL) || (cubreTitular && largo)) {
          n++;
        }
      }
    }
    return n;
  }
}
