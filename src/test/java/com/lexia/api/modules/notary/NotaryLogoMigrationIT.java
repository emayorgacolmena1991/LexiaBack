package com.lexia.api.modules.notary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Postgres 18 embebido. Ejecutar con {@code mvnw test -Dtest=NotaryLogoMigrationIT}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotaryLogoMigrationIT {

  private static final String NOTARIA = "b1000000-0000-7000-8000-000000000002";

  private EmbeddedPostgres postgres;
  private String url;

  @BeforeAll
  void migrar() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    try (Connection admin = postgres.getPostgresDatabase().getConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "CREATE ROLE lexia_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
      st.execute("CREATE ROLE lexia_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS");
      st.execute(
          "CREATE ROLE lexia_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS");
      st.execute("CREATE DATABASE lexia OWNER lexia_migrator ENCODING 'UTF8' TEMPLATE template0");
    }
    url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/lexia";
    Flyway.configure()
        .dataSource(url, "lexia_migrator", "")
        .defaultSchema("public")
        .schemas("public", "control", "app")
        .locations("classpath:db/migration")
        .target("95")
        .load()
        .migrate();
  }

  @AfterAll
  void cerrar() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  private int actualizarTipo(Connection db, String tipo) throws SQLException {
    try (PreparedStatement ps =
        db.prepareStatement(
            """
            UPDATE control.notary_tenant_profile
            SET logo_content_type = ?
            WHERE tenant_id = ?::uuid
            """)) {
      ps.setString(1, tipo);
      ps.setString(2, NOTARIA);
      return ps.executeUpdate();
    }
  }

  @Test
  void laEntidadCalzaConElEsquema() {
    var registro =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", "lexia_app")
            .applySetting("hibernate.connection.password", "")
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();
    try (SessionFactory fabrica =
        new MetadataSources(registro)
            .addAnnotatedClass(NotaryTenantProfile.class)
            .buildMetadata()
            .buildSessionFactory()) {
      assertTrue(fabrica.isOpen());
    }
  }

  @Test
  void lexiaAppPuedeGuardarElLogo() throws Exception {
    try (Connection db = DriverManager.getConnection(url, "lexia_app", "")) {
      assertEquals(1, actualizarTipo(db, "image/png"));
      assertEquals(1, actualizarTipo(db, null));
    }
  }

  @Test
  void soloAceptaPngOJpeg() throws Exception {
    try (Connection db = DriverManager.getConnection(url, "lexia_app", "")) {
      SQLException error =
          assertThrows(SQLException.class, () -> actualizarTipo(db, "image/svg+xml"));
      assertEquals("23514", error.getSQLState());
    }
  }
}
