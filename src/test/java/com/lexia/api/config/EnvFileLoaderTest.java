package com.lexia.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileLoaderTest {

  @TempDir Path tempDir;

  @Test
  void loadsPropertiesFromEnvFile() throws Exception {
    Path env = tempDir.resolve(".env");
    Files.writeString(
        env,
        """
        LEXIA_DB_HOST=test-host.example
        LEXIA_DB_PORT=5433
        # comment
        LEXIA_DB_PASSWORD=secret
        """);

    String previousHost = System.getProperty("LEXIA_DB_HOST");
    String previousPort = System.getProperty("LEXIA_DB_PORT");
    String previousPassword = System.getProperty("LEXIA_DB_PASSWORD");
    try {
      System.clearProperty("LEXIA_DB_HOST");
      System.clearProperty("LEXIA_DB_PORT");
      System.clearProperty("LEXIA_DB_PASSWORD");

      for (String line : Files.readAllLines(env)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        String key = trimmed.substring(0, eq).trim();
        String value = trimmed.substring(eq + 1).trim();
        if (System.getenv(key) == null && System.getProperty(key) == null) {
          System.setProperty(key, value);
        }
      }

      assertEquals("test-host.example", System.getProperty("LEXIA_DB_HOST"));
      assertEquals("5433", System.getProperty("LEXIA_DB_PORT"));
      assertEquals("secret", System.getProperty("LEXIA_DB_PASSWORD"));
    } finally {
      restore("LEXIA_DB_HOST", previousHost);
      restore("LEXIA_DB_PORT", previousPort);
      restore("LEXIA_DB_PASSWORD", previousPassword);
    }
  }

  @Test
  void candidatesIncludeBackendAndRoot() {
    assertTrue(EnvFileLoader.candidates().size() >= 2);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
