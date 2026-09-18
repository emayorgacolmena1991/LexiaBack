package com.lexia.api.modules.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RlsIsolationTest {

  private static final UUID DEMO = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID OTHER = UUID.fromString("b1000000-0000-7000-8000-000000000099");

  private Map<String, String> env;

  @BeforeEach
  void loadEnv() throws Exception {
    env = new HashMap<>();
    env.putAll(System.getenv());
    Path file = Path.of("").toAbsolutePath().resolve(".env");
    if (!Files.exists(file)) {
      file = Path.of("").toAbsolutePath().getParent().resolve(".env");
    }
    if (Files.exists(file)) {
      for (String line : Files.readAllLines(file)) {
        if (line.isBlank() || line.startsWith("#") || !line.contains("=")) {
          continue;
        }
        int eq = line.indexOf('=');
        env.putIfAbsent(line.substring(0, eq), line.substring(eq + 1));
      }
    }
    assumeTrue(env.getOrDefault("LEXIA_DB_HOST", "").length() > 0, "Sin LEXIA_DB_HOST");
    assumeTrue(env.getOrDefault("LEXIA_DB_PASSWORD", "").length() > 0, "Sin LEXIA_DB_PASSWORD");
  }

  @Test
  void appRoleSeesOnlyCurrentTenantRows() throws Exception {
    String host = env.get("LEXIA_DB_HOST");
    String port = env.getOrDefault("LEXIA_DB_PORT", "5432");
    String url = "jdbc:postgresql://" + host + ":" + port + "/lexia";

    try (Connection migrator =
        DriverManager.getConnection(
            url, env.get("LEXIA_FLYWAY_USER"), env.get("LEXIA_FLYWAY_PASSWORD"))) {
      migrator.setAutoCommit(false);
      try (PreparedStatement insertTenant =
              migrator.prepareStatement(
                  "INSERT INTO control.tenant (id, code, name, status, isolation_mode)"
                      + " VALUES (?, 'rls-it', 'RLS Isolation', 'ACTIVE', 'SHARED')"
                      + " ON CONFLICT (id) DO NOTHING");
          PreparedStatement insertEntity =
              migrator.prepareStatement(
                  "INSERT INTO app.legal_entity (id, tenant_id, legal_name, entity_type, status)"
                      + " VALUES (?, ?, 'Entidad IT', 'COMPANY', 'ACTIVE')"
                      + " ON CONFLICT (id, tenant_id) DO NOTHING")) {
        insertTenant.setObject(1, OTHER);
        insertTenant.executeUpdate();
        insertEntity.setObject(1, UUID.fromString("c1000000-0000-7000-8000-000000000099"));
        insertEntity.setObject(2, OTHER);
        insertEntity.executeUpdate();
      }
      migrator.commit();
    }

    try (Connection app =
        DriverManager.getConnection(url, env.get("LEXIA_DB_USER"), env.get("LEXIA_DB_PASSWORD"))) {
      assertEquals(0, countEntities(app, TenantContext.UNSET));
      assertEquals(1, countEntities(app, DEMO.toString()));
      assertEquals(1, countEntities(app, OTHER.toString()));
      assertEquals(0, countOtherFromDemo(app));
    }

    try (Connection migrator =
        DriverManager.getConnection(
            url, env.get("LEXIA_FLYWAY_USER"), env.get("LEXIA_FLYWAY_PASSWORD"))) {
      try (PreparedStatement deleteEntity =
              migrator.prepareStatement("DELETE FROM app.legal_entity WHERE tenant_id = ?");
          PreparedStatement deleteTenant =
              migrator.prepareStatement("DELETE FROM control.tenant WHERE id = ?")) {
        deleteEntity.setObject(1, OTHER);
        deleteEntity.executeUpdate();
        deleteTenant.setObject(1, OTHER);
        deleteTenant.executeUpdate();
      }
    }
  }

  private static int countEntities(Connection connection, String tenant) throws Exception {
    RlsConnectionProvider.applyTenant(connection, tenant);
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM app.legal_entity");
        ResultSet rs = statement.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static int countOtherFromDemo(Connection connection) throws Exception {
    RlsConnectionProvider.applyTenant(connection, DEMO.toString());
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT count(*) FROM app.legal_entity WHERE tenant_id = ?")) {
      statement.setObject(1, OTHER);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
