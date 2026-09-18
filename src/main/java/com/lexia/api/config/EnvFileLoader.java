package com.lexia.api.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Carga backend/.env al arranque para IDE y mvn sin export manual. */
public final class EnvFileLoader {

  private static final Logger log = LoggerFactory.getLogger(EnvFileLoader.class);

  private EnvFileLoader() {}

  public static void loadIfPresent() {
    Path loaded = null;
    for (Path candidate : candidates()) {
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      try {
        applyFile(candidate);
        loaded = candidate;
        break;
      } catch (IOException ex) {
        throw new IllegalStateException("No se pudo leer " + candidate.toAbsolutePath(), ex);
      }
    }
    if (loaded != null) {
      log.info("Variables cargadas desde {}", loaded.toAbsolutePath());
    } else {
      log.warn(
          "No se encontró .env. Use backend/.env o exporte LEXIA_DB_* antes de arrancar.");
    }
  }

  static List<Path> candidates() {
    Set<Path> paths = new LinkedHashSet<>();
    String override = System.getenv("LEXIA_ENV_FILE");
    if (override != null && !override.isBlank()) {
      paths.add(Path.of(override.trim()));
    }
    Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    paths.add(cwd.resolve(".env"));
    paths.add(cwd.resolve("backend").resolve(".env"));
    if (cwd.getFileName() != null && "backend".equals(cwd.getFileName().toString())) {
      paths.add(cwd.getParent().resolve(".env"));
    }
    return new ArrayList<>(paths);
  }

  private static void applyFile(Path file) throws IOException {
    for (String line : Files.readAllLines(file)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = trimmed.substring(0, eq).trim();
      String value = trimmed.substring(eq + 1).trim();
      if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
        value = value.substring(1, value.length() - 1);
      }
      if (System.getenv(key) == null && System.getProperty(key) == null) {
        System.setProperty(key, value);
      }
    }
  }
}
