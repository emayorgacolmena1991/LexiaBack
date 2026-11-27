package com.lexia.api.common.storage.migracion;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("migrar-storage")
public class StorageMigracionService {

  private static final Logger LOG = LoggerFactory.getLogger(StorageMigracionService.class);
  private static final String CURSOR_INICIAL = "00000000-0000-0000-0000-000000000000";

  private final JdbcTemplate jdbc;
  private final StorageMigracionPaths paths;
  private final StorageMigracionObjectStore bucket;
  private final StorageMigracionOpciones opciones;

  public StorageMigracionService(
      JdbcTemplate jdbc,
      @Value("${lexia.storage.local-root:./data}") String localRoot,
      @Value("${lexia.expedientes.storage-dir:./data/expedientes}") String expedientes,
      @Value("${lexia.minutas.storage-dir:./data/minutas}") String minutas,
      @Value("${lexia.coactivas.storage-dir:./data/coactivas}") String coactivas,
      StorageMigracionObjectStore bucket,
      StorageMigracionOpciones opciones) {
    this.jdbc = jdbc;
    this.paths =
        new StorageMigracionPaths(
            Path.of(localRoot),
            Path.of(expedientes),
            Path.of(minutas),
            Path.of(coactivas),
            Path.of(coactivas));
    this.bucket = bucket;
    this.opciones = opciones;
  }

  public int ejecutar() throws IOException {
    LOG.info(
        "Migración storage inicio dryRun={} lote={} tablas={}",
        opciones.dryRun(),
        opciones.lote(),
        opciones.tablas().isEmpty() ? "todas" : opciones.tablas());

    Path reporte = Path.of(reporteNombre());
    List<StorageMigracionTabla> tablas = tablasActivas();
    List<String> resumenLineas = new ArrayList<>();

    try (BufferedWriter csv = Files.newBufferedWriter(reporte, StandardCharsets.UTF_8)) {
      csv.write("tabla,id,clave_antes,clave_despues,resultado,detalle\n");
      for (StorageMigracionTabla tabla : tablas) {
        StorageMigracionResumen resumen = migrarTabla(tabla, csv);
        resumenLineas.add(resumenTabla(tabla.nombre(), resumen));
        LOG.info("Tabla {}: {}", tabla.nombre(), resumenTabla(tabla.nombre(), resumen));
      }
    }

    LOG.info("Reporte CSV: {}", reporte.toAbsolutePath());
    for (String linea : resumenLineas) {
      LOG.info(linea);
    }
    return resumenLineas.stream().anyMatch(l -> l.contains("errores=") && !l.endsWith("errores=0")) ? 1 : 0;
  }

  private StorageMigracionResumen migrarTabla(StorageMigracionTabla tabla, BufferedWriter csv)
      throws IOException {
    StorageMigracionResumen resumen = new StorageMigracionResumen();
    String cursor = CURSOR_INICIAL;
    while (true) {
      List<StorageMigracionProcesador.Fila> lote =
          jdbc.query(
              tabla.select(),
              (rs, rowNum) ->
                  new StorageMigracionProcesador.Fila(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getString(4)),
              cursor,
              opciones.lote());
      if (lote.isEmpty()) {
        break;
      }
      for (StorageMigracionProcesador.Fila fila : lote) {
        StorageMigracionProcesador.Salida salida =
            StorageMigracionProcesador.procesar(fila, paths, bucket, opciones.dryRun());
        resumen.acumular(salida);
        if (!opciones.dryRun()
            && StorageMigracionProcesador.requiereUpdate(salida)
            && salida.claveDespues() != null) {
          jdbc.update(tabla.update(), salida.claveDespues(), fila.id());
        }
        csv.write(csvLinea(tabla.nombre(), fila.id(), salida));
      }
      cursor = lote.get(lote.size() - 1).id();
      LOG.info("Tabla {}: lote procesado hasta id={}", tabla.nombre(), cursor);
      if (lote.size() < opciones.lote()) {
        break;
      }
    }
    return resumen;
  }

  private List<StorageMigracionTabla> tablasActivas() {
    Set<String> filtro = opciones.tablas();
    if (filtro.isEmpty()) {
      return Arrays.asList(StorageMigracionTabla.values());
    }
    return Arrays.stream(StorageMigracionTabla.values())
        .filter(t -> filtro.contains(t.nombre()))
        .toList();
  }

  private static String reporteNombre() {
    String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    return "migracion-storage-reporte-" + fecha + ".csv";
  }

  private static String resumenTabla(String tabla, StorageMigracionResumen r) {
    return String.format(
        Locale.ROOT,
        "%s: subidos=%d ya_estaban=%d actualizadas=%d faltantes=%d errores=%d dry_run=%d bytes=%d",
        tabla,
        r.subidos,
        r.yaEstaban,
        r.actualizadas,
        r.faltantes,
        r.errores,
        r.dryRun,
        r.bytesSubidos);
  }

  private static String csvLinea(String tabla, String id, StorageMigracionProcesador.Salida salida)
      throws IOException {
    return escape(tabla)
        + ","
        + escape(id)
        + ","
        + escape(salida.claveAntes())
        + ","
        + escape(salida.claveDespues())
        + ","
        + salida.resultado()
        + ","
        + escape(salida.detalle())
        + "\n";
  }

  private static String escape(String valor) {
    if (valor == null) {
      return "";
    }
    String v = valor.replace("\"", "\"\"");
    if (v.contains(",") || v.contains("\n") || v.contains("\"")) {
      return "\"" + v + "\"";
    }
    return v;
  }
}