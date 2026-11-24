package com.lexia.api.modules.expedientes.coactivas.ingesta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Campo;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.DetectorCabecera;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaColumnas;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ColumnaActa;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Mapeo manual de columnas de actas y formatos recordados. Sin estado ni base de datos: el
 * servicio le pasa las tablas guardadas y los formatos del tenant.
 */
final class ActaFormatos {

  private static final ObjectMapper JSON = new ObjectMapper();
  /** Por encima de esto no se guardan las tablas (actas enormes): no se podrá remapear. */
  static final int MAX_CHARS_TABLAS = 2_000_000;
  private static final int FILAS_VISTA = 10;
  private static final int EJEMPLOS = 3;
  private static final int MAX_CELDA_VISTA = 60;

  /** Campos que se pueden asignar a mano, en el orden en que se muestran. */
  static final List<String> CAMPOS =
      List.of("JUICIO", "DEUDOR", "CEDULA", "OPERACION", "OFICINA", "ETAPA", "FOJAS", "ANIO", "ZONAL", "UEC");

  private ActaFormatos() {}

  /** Formato guardado listo para aplicar. */
  record Guardado(String nombre, Map<Campo, Integer> mapeo) {}

  // ---------------------------------------------------------------------------
  // Serialización
  // ---------------------------------------------------------------------------

  /** JSON de las tablas, o null si es demasiado grande para guardarlo. */
  static String serializar(List<List<List<String>>> tablas) {
    try {
      String json = JSON.writeValueAsString(tablas);
      return json.length() <= MAX_CHARS_TABLAS ? json : null;
    } catch (Exception ex) {
      return null;
    }
  }

  static List<List<List<String>>> deserializar(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return JSON.readValue(json, new TypeReference<List<List<List<String>>>>() {});
    } catch (Exception ex) {
      return List.of();
    }
  }

  static String mapeoAJson(Map<Campo, Integer> mapeo) {
    Map<String, String> plano = new TreeMap<>();
    mapeo.forEach((campo, col) -> plano.put(String.valueOf(col), campo.name()));
    try {
      return JSON.writeValueAsString(plano);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  static Map<Campo, Integer> mapeoDesdeJson(String json) {
    Map<Campo, Integer> mapeo = new EnumMap<>(Campo.class);
    if (json == null || json.isBlank()) {
      return mapeo;
    }
    try {
      Map<String, String> plano = JSON.readValue(json, new TypeReference<Map<String, String>>() {});
      plano.forEach(
          (col, campo) -> {
            try {
              mapeo.putIfAbsent(Campo.valueOf(campo), Integer.parseInt(col));
            } catch (RuntimeException ignored) {
              // Entrada corrupta: se ignora esa columna.
            }
          });
    } catch (Exception ex) {
      return mapeo;
    }
    return mapeo;
  }

  static String celdasAJson(List<String> celdas) {
    try {
      return JSON.writeValueAsString(celdas);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  // ---------------------------------------------------------------------------
  // Detectores
  // ---------------------------------------------------------------------------

  /**
   * Primero un formato guardado cuya firma coincida con la fila; si no, palabras clave.
   * {@code alAplicar} recibe la firma del formato guardado que se usó.
   */
  static DetectorCabecera conGuardados(Map<String, Guardado> porFirma, Consumer<String> alAplicar) {
    return row -> {
      String firma = ActaTableParser.firma(row);
      Guardado guardado = firma == null ? null : porFirma.get(firma);
      if (guardado != null && !guardado.mapeo().isEmpty()) {
        alAplicar.accept(firma);
        return Optional.of(guardado.mapeo());
      }
      return ActaTableParser.POR_PALABRAS.detectar(row);
    };
  }

  /** Solo la fila con esa firma es cabecera (mapeo manual del usuario). */
  static DetectorCabecera exacto(String firma, Map<Campo, Integer> mapeo) {
    return row -> firma.equals(ActaTableParser.firma(row)) ? Optional.of(mapeo) : Optional.empty();
  }

  /**
   * Valida el mapeo que manda el usuario: campos conocidos, sin repetir, y al menos juicio o
   * deudor. Lanza {@link IllegalArgumentException} con un mensaje para mostrar.
   */
  static Map<Campo, Integer> validarMapeo(Map<Integer, String> pedido, int columnas) {
    Map<Campo, Integer> mapeo = new EnumMap<>(Campo.class);
    if (pedido != null) {
      for (Map.Entry<Integer, String> e : new TreeMap<>(pedido).entrySet()) {
        String nombre = CoactivaTexto.blankToNull(e.getValue());
        if (nombre == null || "IGNORAR".equalsIgnoreCase(nombre)) {
          continue;
        }
        Integer col = e.getKey();
        if (col == null || col < 0 || col >= columnas) {
          throw new IllegalArgumentException("La columna " + col + " no existe en la tabla.");
        }
        Campo campo;
        try {
          campo = Campo.valueOf(nombre.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException("Campo desconocido: " + nombre + ".");
        }
        if (mapeo.putIfAbsent(campo, col) != null) {
          throw new IllegalArgumentException("El campo " + campo.name() + " está asignado a dos columnas.");
        }
      }
    }
    if (!mapeo.containsKey(Campo.JUICIO) && !mapeo.containsKey(Campo.DEUDOR)) {
      throw new IllegalArgumentException("Asigna al menos la columna del juicio o la del deudor.");
    }
    return mapeo;
  }

  // ---------------------------------------------------------------------------
  // Vista de columnas
  // ---------------------------------------------------------------------------

  /** Tabla por defecto: la primera con cabecera reconocible; si ninguna, la de más columnas. */
  static int tablaPorDefecto(List<List<List<String>>> tablas) {
    for (int t = 0; t < tablas.size(); t++) {
      if (ActaTableParser.filaCabecera(tablas.get(t)) >= 0) {
        return t;
      }
    }
    int mejor = 0;
    int max = -1;
    for (int t = 0; t < tablas.size(); t++) {
      int cols = columnas(tablas.get(t));
      if (cols > max) {
        max = cols;
        mejor = t;
      }
    }
    return mejor;
  }

  /** Cabecera por defecto: la de palabras clave, o la primera fila con al menos dos celdas. */
  static int filaPorDefecto(List<List<String>> grid) {
    int porPalabras = ActaTableParser.filaCabecera(grid);
    if (porPalabras >= 0) {
      return porPalabras;
    }
    for (int r = 0; r < Math.min(ActaTableParser.HEADER_SCAN_ROWS, grid.size()); r++) {
      if (grid.get(r).stream().filter(c -> c != null && !c.isBlank()).count() >= 2) {
        return r;
      }
    }
    return 0;
  }

  static ActaColumnas columnas(
      List<List<List<String>>> tablas,
      Integer tablaPedida,
      Integer filaPedida,
      Map<String, Guardado> porFirma,
      boolean editable) {
    int tabla = tablaPedida != null ? tablaPedida : tablaPorDefecto(tablas);
    if (tabla < 0 || tabla >= tablas.size()) {
      throw new IllegalArgumentException("La tabla " + tabla + " no existe en el acta.");
    }
    List<List<String>> grid = tablas.get(tabla);
    int fila = filaPedida != null ? filaPedida : filaPorDefecto(grid);
    if (fila < 0 || fila >= Math.min(ActaTableParser.HEADER_SCAN_ROWS, grid.size())) {
      throw new IllegalArgumentException("La cabecera debe estar en las primeras " + ActaTableParser.HEADER_SCAN_ROWS + " filas.");
    }
    List<String> cabecera = grid.get(fila);
    int cols = columnas(grid);

    Map<Integer, String> asignado = new LinkedHashMap<>();
    String formatoNombre = null;
    String firma = ActaTableParser.firma(cabecera);
    Guardado guardado = firma == null ? null : porFirma.get(firma);
    Map<Campo, Integer> base;
    if (guardado != null) {
      formatoNombre = guardado.nombre();
      base = guardado.mapeo();
    } else {
      base = ActaTableParser.detectarCabecera(cabecera);
    }
    base.forEach((campo, col) -> asignado.put(col, campo.name()));

    List<ColumnaActa> columnas = new ArrayList<>();
    for (int c = 0; c < cols; c++) {
      columnas.add(
          new ColumnaActa(c, celda(cabecera, c), ejemplos(grid, fila, c), asignado.get(c)));
    }
    List<List<String>> vista = new ArrayList<>();
    for (int r = 0; r < Math.min(FILAS_VISTA, grid.size()); r++) {
      vista.add(grid.get(r).stream().map(v -> CoactivaTexto.truncate(v == null ? "" : v, MAX_CELDA_VISTA)).toList());
    }
    return new ActaColumnas(tabla, tablas.size(), fila, vista, columnas, CAMPOS, formatoNombre, editable);
  }

  static int columnas(List<List<String>> grid) {
    return grid.stream().mapToInt(List::size).max().orElse(0);
  }

  private static List<String> ejemplos(List<List<String>> grid, int fila, int col) {
    List<String> ejemplos = new ArrayList<>();
    for (int r = fila + 1; r < grid.size() && ejemplos.size() < EJEMPLOS; r++) {
      String v = celda(grid.get(r), col);
      if (v != null) {
        ejemplos.add(CoactivaTexto.truncate(v, MAX_CELDA_VISTA));
      }
    }
    return ejemplos;
  }

  private static String celda(List<String> row, int col) {
    return col < row.size() ? CoactivaTexto.blankToNull(row.get(col)) : null;
  }

  /** Nombre legible por defecto para un formato: las primeras celdas de la cabecera. */
  static String nombrePorDefecto(List<String> cabecera) {
    String unido =
        String.join(
            " · ",
            cabecera.stream().map(CoactivaTexto::blankToNull).filter(java.util.Objects::nonNull).limit(4).toList());
    return CoactivaTexto.truncate(unido.isEmpty() ? "Formato de acta" : unido, 160);
  }
}
