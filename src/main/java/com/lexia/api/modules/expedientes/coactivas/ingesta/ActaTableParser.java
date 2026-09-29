package com.lexia.api.modules.expedientes.coactivas.ingesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Convierte tablas de actas de entrega (Excel/CSV o tablas de Azure {@code prebuilt-layout}) en
 * filas normalizadas. La cabecera se detecta por palabras clave en las primeras filas; si una tabla
 * no tiene cabecera (continuación en otra página) se reutiliza la última detectada con el mismo
 * número de columnas.
 */
public final class ActaTableParser {

  private static final int HEADER_SCAN_ROWS = 10;

  enum Campo {
    OFICINA,
    OPERACION,
    JUICIO,
    ANIO,
    DEUDOR,
    CEDULA,
    ETAPA,
    FOJAS
  }

  public record FilaActa(
      int fila,
      String oficina,
      String operacion,
      String juicio,
      String deudor,
      String cedula,
      String etapa,
      Integer fojas) {}

  public record Resultado(List<FilaActa> filas, List<String> advertencias) {}

  private ActaTableParser() {}

  // ---------------------------------------------------------------------------
  // Fuentes
  // ---------------------------------------------------------------------------

  public static List<List<List<String>>> leerExcel(byte[] bytes) throws IOException {
    List<List<List<String>>> tablas = new ArrayList<>();
    DataFormatter formatter = new DataFormatter();
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
        Sheet sheet = workbook.getSheetAt(s);
        List<List<String>> grid = new ArrayList<>();
        for (Row row : sheet) {
          List<String> cells = new ArrayList<>();
          short last = row.getLastCellNum();
          for (int c = 0; c < Math.max(last, 0); c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
          }
          grid.add(cells);
        }
        if (!grid.isEmpty()) {
          tablas.add(grid);
        }
      }
    }
    return tablas;
  }

  public static List<List<List<String>>> leerCsv(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.startsWith("\uFEFF")) {
      text = text.substring(1);
    }
    String[] lines = text.split("\\r?\\n");
    String sep = lines.length > 0 && lines[0].contains(";") ? ";" : ",";
    List<List<String>> grid = new ArrayList<>();
    for (String line : lines) {
      List<String> cells = new ArrayList<>();
      for (String cell : line.split(sep, -1)) {
        cells.add(cell.replaceAll("^\"|\"$", "").trim());
      }
      grid.add(cells);
    }
    return List.of(grid);
  }

  /** Tablas del {@code analyzeResult} de Azure Document Intelligence (modelo prebuilt-layout). */
  public static List<List<List<String>>> leerAzureLayout(JsonNode analyzeResult) {
    List<List<List<String>>> tablas = new ArrayList<>();
    JsonNode tables = analyzeResult == null ? null : analyzeResult.path("tables");
    if (tables == null || !tables.isArray()) {
      return tablas;
    }
    for (JsonNode table : tables) {
      int rows = table.path("rowCount").asInt(0);
      int cols = table.path("columnCount").asInt(0);
      if (rows == 0 || cols == 0) {
        continue;
      }
      String[][] grid = new String[rows][cols];
      for (JsonNode cell : table.path("cells")) {
        int r = cell.path("rowIndex").asInt(-1);
        int c = cell.path("columnIndex").asInt(-1);
        if (r >= 0 && r < rows && c >= 0 && c < cols) {
          grid[r][c] = cell.path("content").asText("").replace(":selected:", "").replace(":unselected:", "").trim();
        }
      }
      List<List<String>> list = new ArrayList<>();
      for (String[] row : grid) {
        List<String> cells = new ArrayList<>();
        for (String value : row) {
          cells.add(value == null ? "" : value);
        }
        list.add(cells);
      }
      tablas.add(list);
    }
    return tablas;
  }

  // ---------------------------------------------------------------------------
  // Interpretación
  // ---------------------------------------------------------------------------

  public static Resultado interpretar(List<List<List<String>>> tablas) {
    List<FilaActa> filas = new ArrayList<>();
    List<String> advertencias = new ArrayList<>();
    Map<Campo, Integer> ultimaCabecera = null;
    int ultimaColumnas = -1;
    int numero = 1;

    for (int t = 0; t < tablas.size(); t++) {
      List<List<String>> grid = tablas.get(t);
      int columnas = grid.stream().mapToInt(List::size).max().orElse(0);
      int headerRow = -1;
      Map<Campo, Integer> cabecera = null;
      for (int r = 0; r < Math.min(HEADER_SCAN_ROWS, grid.size()); r++) {
        Map<Campo, Integer> candidata = detectarCabecera(grid.get(r));
        if (candidata.size() >= 2
            && (candidata.containsKey(Campo.JUICIO) || candidata.containsKey(Campo.DEUDOR))) {
          headerRow = r;
          cabecera = candidata;
          break;
        }
      }
      if (cabecera == null && ultimaCabecera != null && columnas == ultimaColumnas) {
        cabecera = ultimaCabecera;
      }
      if (cabecera == null) {
        List<FilaActa> heuristicas = heuristica(grid, numero);
        if (!heuristicas.isEmpty()) {
          advertencias.add(
              "Tabla " + (t + 1) + ": sin cabecera reconocible; se detectaron juicios por patrón. Revisa las filas.");
          filas.addAll(heuristicas);
          numero += heuristicas.size();
        }
        continue;
      }
      ultimaCabecera = cabecera;
      ultimaColumnas = columnas;
      for (int r = headerRow + 1; r < grid.size(); r++) {
        List<String> row = grid.get(r);
        FilaActa fila = mapear(row, cabecera, numero);
        if (fila != null) {
          filas.add(fila);
          numero++;
        }
      }
    }
    if (filas.isEmpty()) {
      advertencias.add("No se encontraron filas de expedientes en el archivo.");
    }
    return new Resultado(filas, advertencias);
  }

  static Map<Campo, Integer> detectarCabecera(List<String> row) {
    Map<Campo, Integer> map = new EnumMap<>(Campo.class);
    for (int c = 0; c < row.size(); c++) {
      String h = CoactivaTexto.claveNombre(row.get(c));
      if (h.isEmpty() || h.length() > 60) {
        continue;
      }
      final int column = c;
      campoDe(h).ifPresent(campo -> map.putIfAbsent(campo, column));
    }
    return map;
  }

  static Optional<Campo> campoDe(String h) {
    if (h.contains("CEDULA") || h.contains("IDENTIFICACI") || h.equals("CI") || h.startsWith("C I")
        || h.contains("RUC")) {
      return Optional.of(Campo.CEDULA);
    }
    if (h.contains("OPERACI")) {
      return Optional.of(Campo.OPERACION);
    }
    if (h.contains("ETAPA") || h.contains("ESTADO")) {
      return Optional.of(Campo.ETAPA);
    }
    if (h.contains("JUICIO") || h.contains("PROCESO") || h.contains("CAUSA") || h.contains("COACTIV")) {
      return Optional.of(Campo.JUICIO);
    }
    if (h.equals("ANO") || h.equals("ANIO") || h.startsWith("ANO ") || h.endsWith(" ANO") || h.contains("ANIO")) {
      return Optional.of(Campo.ANIO);
    }
    if (h.contains("FOJA")) {
      return Optional.of(Campo.FOJAS);
    }
    if (h.contains("OFICINA") || h.contains("AGENCIA") || h.contains("SUCURSAL")) {
      return Optional.of(Campo.OFICINA);
    }
    if (h.contains("DEUDOR") || h.contains("NOMBRE") || h.contains("CLIENTE") || h.contains("TITULAR")
        || h.contains("APELLIDO") || h.contains("COACTIVADO")) {
      return Optional.of(Campo.DEUDOR);
    }
    return Optional.empty();
  }

  private static FilaActa mapear(List<String> row, Map<Campo, Integer> cabecera, int numero) {
    String oficina = celda(row, cabecera.get(Campo.OFICINA));
    String operacion = celda(row, cabecera.get(Campo.OPERACION));
    String juicioRaw = celda(row, cabecera.get(Campo.JUICIO));
    String deudor = celda(row, cabecera.get(Campo.DEUDOR));
    String cedula = celda(row, cabecera.get(Campo.CEDULA));
    String etapa = celda(row, cabecera.get(Campo.ETAPA));
    Integer fojas = CoactivaTexto.parseEntero(celda(row, cabecera.get(Campo.FOJAS)));

    if (juicioRaw == null) {
      juicioRaw = CoactivaTexto.detectarJuicio(String.join(" ", row)).orElse(null);
    }
    if (juicioRaw == null && deudor == null && operacion == null) {
      return null;
    }
    if (deudor != null && CoactivaTexto.claveNombre(deudor).matches("TOTAL.*|SUBTOTAL.*")) {
      return null;
    }
    return new FilaActa(
        numero, oficina, operacion, CoactivaTexto.normalizarJuicio(juicioRaw), deudor, cedula, etapa, fojas);
  }

  private static List<FilaActa> heuristica(List<List<String>> grid, int desde) {
    List<FilaActa> filas = new ArrayList<>();
    int numero = desde;
    for (List<String> row : grid) {
      Optional<String> juicio = CoactivaTexto.detectarJuicio(String.join(" ", row));
      if (juicio.isEmpty()) {
        continue;
      }
      String deudor = null;
      String cedula = null;
      for (String cell : row) {
        String digits = CoactivaTexto.soloDigitos(cell);
        if (cedula == null && (digits.length() == 10 || digits.length() == 13) && digits.length() == cell.trim().length()) {
          cedula = digits;
        } else if (cell.matches(".*[A-Za-zÁÉÍÓÚÑáéíóúñ]{3,}.*")
            && (deudor == null || cell.length() > deudor.length())) {
          deudor = cell.trim();
        }
      }
      filas.add(new FilaActa(numero++, null, null, juicio.get(), deudor, cedula, null, null));
    }
    return filas;
  }

  private static String celda(List<String> row, Integer index) {
    if (index == null || index < 0 || index >= row.size()) {
      return null;
    }
    return CoactivaTexto.blankToNull(row.get(index));
  }
}
