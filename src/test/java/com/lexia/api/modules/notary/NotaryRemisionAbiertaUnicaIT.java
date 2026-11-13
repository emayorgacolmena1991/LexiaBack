package com.lexia.api.modules.notary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.common.api.ApiExceptionHandler;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/** Postgres 18 embebido. Ejecutar con {@code mvnw test -Dtest=NotaryRemisionAbiertaUnicaIT}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotaryRemisionAbiertaUnicaIT {

  private static final String BUFETE = "b1000000-0000-7000-8000-000000000001";
  private static final String NOTARIA = "b1000000-0000-7000-8000-000000000002";

  private EmbeddedPostgres postgres;

  @BeforeAll
  void arrancar() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    try (Connection admin = postgres.getPostgresDatabase().getConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "CREATE ROLE lexia_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
      st.execute("CREATE ROLE lexia_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS");
      st.execute(
          "CREATE ROLE lexia_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS");
    }
  }

  @AfterAll
  void cerrar() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  void v97FallaListandoLosIdsDuplicados() throws Exception {
    String base = "lexia_v97_dup";
    crearBase(base);
    flyway(base, "96").migrate();
    UUID primera = UUID.randomUUID();
    UUID segunda = UUID.randomUUID();
    UUID caso = UUID.randomUUID();
    try (Connection db = conectar(base)) {
      sembrar(db, primera, caso, null);
      sembrar(db, segunda, caso, null);
    }

    FlywayException error = assertThrows(FlywayException.class, () -> flyway(base, "97").migrate());

    String texto = cadena(error);
    assertTrue(texto.contains("V97: remisiones abiertas duplicadas"), texto);
    assertTrue(texto.contains(primera.toString()), texto);
    assertTrue(texto.contains(segunda.toString()), texto);
  }

  @Test
  void v97AplicaYElSegundoAbiertoLoTraduceElHandler() throws Exception {
    String base = "lexia_v97_ok";
    crearBase(base);
    flyway(base, "97").migrate();
    UUID caso = UUID.randomUUID();
    try (Connection db = conectar(base)) {
      sembrar(db, UUID.randomUUID(), caso, "DEVUELTO_AL_BUFETE");
      sembrar(db, UUID.randomUUID(), caso, null);
      SQLException sql =
          assertThrows(SQLException.class, () -> sembrar(db, UUID.randomUUID(), caso, null));
      assertEquals("23505", sql.getSQLState());

      var respuesta =
          new ApiExceptionHandler().integrity(new DataIntegrityViolationException("dup", sql));

      assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
      assertEquals("El expediente ya tiene una remisión abierta.", respuesta.getBody().get("message"));
      sembrar(db, UUID.randomUUID(), caso, "NOTARIA_COMPLETADA");
    }
  }

  private void crearBase(String nombre) throws SQLException {
    try (Connection admin = postgres.getPostgresDatabase().getConnection();
        Statement st = admin.createStatement()) {
      st.execute("CREATE DATABASE " + nombre + " OWNER lexia_migrator ENCODING 'UTF8' TEMPLATE template0");
    }
  }

  private Flyway flyway(String base, String target) {
    return Flyway.configure()
        .dataSource(url(base), "lexia_migrator", "")
        .defaultSchema("public")
        .schemas("public", "control", "app")
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  private Connection conectar(String base) throws SQLException {
    return DriverManager.getConnection(url(base), "lexia_migrator", "");
  }

  private String url(String base) {
    return "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + base;
  }

  private static void sembrar(Connection db, UUID id, UUID caso, String estado) throws SQLException {
    String sql =
        estado == null
            ? """
            INSERT INTO control.notary_remision (
                id, firm_tenant_id, notary_tenant_id, firm_legal_case_id, expediente_codigo
            ) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'LEX-IT')
            """
            : """
            INSERT INTO control.notary_remision (
                id, firm_tenant_id, notary_tenant_id, firm_legal_case_id, expediente_codigo, estado
            ) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'LEX-IT', ?)
            """;
    try (PreparedStatement ps = db.prepareStatement(sql)) {
      ps.setString(1, id.toString());
      ps.setString(2, BUFETE);
      ps.setString(3, NOTARIA);
      ps.setString(4, caso.toString());
      if (estado != null) {
        ps.setString(5, estado);
      }
      ps.executeUpdate();
    }
  }

  private static String cadena(Throwable error) {
    StringBuilder texto = new StringBuilder();
    while (error != null) {
      if (error.getMessage() != null) {
        texto.append(error.getMessage()).append('\n');
      }
      error = error.getCause();
    }
    return texto.toString();
  }
}
