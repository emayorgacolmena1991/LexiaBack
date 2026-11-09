package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import jakarta.persistence.PersistenceException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Corre sobre un Postgres 16 embebido, nunca sobre la base remota. Surefire no incluye {@code *IT}
 * por defecto: ejecutar con {@code mvnw test -Dtest=NotaryRemisionVersionMigrationIT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotaryRemisionVersionMigrationIT {

  private static final String ANTERIOR = "93";
  private static final String NUEVA = "94";
  private static final String BUFETE = "b1000000-0000-7000-8000-000000000001";
  private static final String NOTARIA = "b1000000-0000-7000-8000-000000000002";
  private static final UUID CON_DOCUMENTOS = UUID.randomUUID();
  private static final UUID SIN_DOCUMENTOS = UUID.randomUUID();
  /** Remisión enviada con la fase 1 ya aplicada: su versión apunta a un borrador real. */
  private static final UUID CON_BORRADOR = UUID.randomUUID();

  private EmbeddedPostgres postgres;
  private String url;

  @BeforeAll
  void migrarYSembrar() throws Exception {
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

    flyway(ANTERIOR).migrate();
    try (Connection db = conectar()) {
      assertEquals(16, db.getMetaData().getDatabaseMajorVersion());
      assertEquals(ANTERIOR, versionAplicada(db));
      sembrarRemision(db, CON_DOCUMENTOS);
      sembrarRemision(db, SIN_DOCUMENTOS);
      sembrarDocumento(db, CON_DOCUMENTOS, 0);
      sembrarDocumento(db, CON_DOCUMENTOS, 1);
    }
    flyway(NUEVA).migrate();
    try (Connection db = conectar()) {
      sembrarVersionConBorrador(db);
    }
    flyway("latest").migrate();
  }

  /** Expediente, ficha y borrador del bufete; luego una versión que lo referencia. */
  private static void sembrarVersionConBorrador(Connection db) throws SQLException {
    UUID caso = UUID.randomUUID();
    UUID ficha = UUID.randomUUID();
    UUID borrador = UUID.randomUUID();
    ejecutar(
        db,
        """
        INSERT INTO app.legal_case (id, tenant_id, code, case_type, vertical, subject)
        VALUES (?, ?::uuid, 'LEX-IT-V96', 'EJD', 'ESCRITURACION', 'Compraventa')
        """,
        caso,
        BUFETE);
    ejecutar(
        db,
        "INSERT INTO app.writing_file (id, tenant_id, case_id) VALUES (?, ?::uuid, ?)",
        ficha,
        BUFETE,
        caso);
    ejecutar(
        db,
        """
        INSERT INTO app.minuta_draft (id, tenant_id, writing_file_id, status, template_kind, product_code)
        VALUES (?, ?::uuid, ?, 'DRAFT_GENERATED', 'MINUTA_COMPRAVENTA', 'VIV_HIPOTECADA_BIESS')
        """,
        borrador,
        BUFETE,
        ficha);
    sembrarRemision(db, CON_BORRADOR);
    ejecutar(
        db,
        """
        INSERT INTO control.notary_remision_version (remision_id, version_no, payload, minuta_draft_id)
        VALUES (?, 1, '{"nombre_vendedor":"ACME"}'::jsonb, ?)
        """,
        CON_BORRADOR,
        borrador);
  }

  private static void ejecutar(Connection db, String sql, Object... parametros) throws SQLException {
    try (PreparedStatement ps = db.prepareStatement(sql)) {
      for (int i = 0; i < parametros.length; i++) {
        ps.setObject(i + 1, parametros[i]);
      }
      ps.executeUpdate();
    }
  }

  @AfterAll
  void cerrar() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  private Flyway flyway(String destino) {
    return Flyway.configure()
        .dataSource(url, "lexia_migrator", "")
        .defaultSchema("public")
        .schemas("public", "control", "app")
        .locations("classpath:db/migration")
        .target(destino)
        .load();
  }

  private Connection conectar() throws SQLException {
    return DriverManager.getConnection(url, "lexia_migrator", "");
  }

  private static String versionAplicada(Connection db) throws SQLException {
    try (Statement st = db.createStatement();
        ResultSet rs =
            st.executeQuery(
                """
                SELECT version FROM public.flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static void sembrarRemision(Connection db, UUID id) throws SQLException {
    try (PreparedStatement ps =
        db.prepareStatement(
            """
            INSERT INTO control.notary_remision (
                id, firm_tenant_id, notary_tenant_id, firm_legal_case_id,
                expediente_codigo, observaciones_bufete
            ) VALUES (?, ?::uuid, ?::uuid, ?, 'LEX-IT', 'Observación previa')
            """)) {
      ps.setObject(1, id);
      ps.setString(2, BUFETE);
      ps.setString(3, NOTARIA);
      ps.setObject(4, UUID.randomUUID());
      ps.executeUpdate();
    }
  }

  private static void sembrarDocumento(Connection db, UUID remision, int orden) throws SQLException {
    try (PreparedStatement ps =
        db.prepareStatement(
            """
            INSERT INTO control.notary_remision_document (
                remision_id, firm_tenant_id, firm_document_version_id, display_name, sort_order
            ) VALUES (?, ?::uuid, ?, ?, ?)
            """)) {
      ps.setObject(1, remision);
      ps.setString(2, BUFETE);
      ps.setObject(3, UUID.randomUUID());
      ps.setString(4, "doc" + orden + ".pdf");
      ps.setInt(5, orden);
      ps.executeUpdate();
    }
  }

  private long contar(String sql, Object... parametros) throws SQLException {
    try (Connection db = conectar();
        PreparedStatement ps = db.prepareStatement(sql)) {
      for (int i = 0; i < parametros.length; i++) {
        ps.setObject(i + 1, parametros[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private boolean privilegio(String rol, String accion) throws SQLException {
    try (Connection db = conectar();
        PreparedStatement ps =
            db.prepareStatement(
                "SELECT has_table_privilege(?, 'control.notary_remision_version', ?)")) {
      ps.setString(1, rol);
      ps.setString(2, accion);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  @Test
  void aplicoLaMigracionNueva() throws Exception {
    assertEquals(
        1,
        contar(
            "SELECT count(*) FROM public.flyway_schema_history WHERE version = ? AND success",
            NUEVA));
  }

  @Test
  void v96CopiaElProductoDelBorradorDeLaVersion() throws Exception {
    assertEquals(
        1,
        contar(
            """
            SELECT count(*) FROM control.notary_remision_version
            WHERE remision_id = ? AND product_code = 'VIV_HIPOTECADA_BIESS'
            """,
            CON_BORRADOR));
  }

  @Test
  void v96DejaSinProductoLaVersionMigradaSinBorrador() throws Exception {
    assertEquals(
        2,
        contar(
            """
            SELECT count(*) FROM control.notary_remision_version
            WHERE remision_id IN (?, ?) AND minuta_draft_id IS NULL AND product_code IS NULL
            """,
            CON_DOCUMENTOS,
            SIN_DOCUMENTOS));
  }

  @Test
  void escrituraSoloAdmiteSelectEInsert() throws Exception {
    try (Connection db = conectar();
        PreparedStatement ps =
            db.prepareStatement("SELECT has_table_privilege(?, 'control.notary_escritura', ?)")) {
      for (String[] caso :
          new String[][] {
            {"lexia_app", "SELECT", "true"},
            {"lexia_app", "INSERT", "true"},
            {"lexia_app", "UPDATE", "false"},
            {"lexia_app", "DELETE", "false"},
            {"lexia_readonly", "SELECT", "true"},
            {"lexia_readonly", "INSERT", "false"}
          }) {
        ps.setString(1, caso[0]);
        ps.setString(2, caso[1]);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertEquals(Boolean.parseBoolean(caso[2]), rs.getBoolean(1), caso[0] + " " + caso[1]);
        }
      }
    }
  }

  @Test
  void escrituraExigeUnaVersionExistente() {
    SQLException error =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection db = conectar()) {
                ejecutar(
                    db,
                    """
                    INSERT INTO control.notary_escritura
                        (remision_id, version_no, datos_formulario, campos_vacios, storage_ref, file_name)
                    VALUES (?, 9, '{}'::jsonb, '[]'::jsonb, '/x.docx', 'x.docx')
                    """,
                    CON_DOCUMENTOS);
              }
            });
    assertEquals("23503", error.getSQLState());
  }

  @Test
  void hayUnaVersion1PorRemision() throws Exception {
    assertEquals(
        contar("SELECT count(*) FROM control.notary_remision"),
        contar("SELECT count(*) FROM control.notary_remision_version"));
    for (UUID remision : new UUID[] {CON_DOCUMENTOS, SIN_DOCUMENTOS}) {
      assertEquals(
          1,
          contar(
              """
              SELECT count(*) FROM control.notary_remision_version
              WHERE remision_id = ? AND version_no = 1
                AND observaciones_bufete = 'Observación previa'
              """,
              remision));
    }
  }

  @Test
  void elPayloadEsObjetoVacio() throws Exception {
    assertEquals(
        0,
        contar(
            """
            SELECT count(*) FROM control.notary_remision_version
            WHERE payload <> '{}'::jsonb AND remision_id IN (?, ?)
            """,
            CON_DOCUMENTOS,
            SIN_DOCUMENTOS));
  }

  @Test
  void losDocumentosCuelganDeLaVersionDeSuRemision() throws Exception {
    assertEquals(
        2,
        contar(
            """
            SELECT count(*)
            FROM control.notary_remision_document d
            JOIN control.notary_remision_version v
              ON v.id = d.version_id AND v.remision_id = d.remision_id
            WHERE d.remision_id = ?
            """,
            CON_DOCUMENTOS));
    assertEquals(
        0,
        contar("SELECT count(*) FROM control.notary_remision_document WHERE version_id IS NULL"));
    assertEquals(
        0,
        contar(
            "SELECT count(*) FROM control.notary_remision_document WHERE remision_id = ?",
            SIN_DOCUMENTOS));
  }

  @Test
  void versionIdEsObligatorio() {
    SQLException error =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection db = conectar()) {
                sembrarDocumento(db, SIN_DOCUMENTOS, 9);
              }
            });
    assertEquals("23502", error.getSQLState());
  }

  @Test
  void uniqueRemisionVersionNoEstaActivo() {
    SQLException error =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection db = conectar();
                  PreparedStatement ps =
                      db.prepareStatement(
                          """
                          INSERT INTO control.notary_remision_version (remision_id, version_no, payload)
                          VALUES (?, 1, '{}'::jsonb)
                          """)) {
                ps.setObject(1, CON_DOCUMENTOS);
                ps.executeUpdate();
              }
            });
    assertEquals("23505", error.getSQLState());
    assertThat(error.getMessage()).contains("ux_notary_remision_version");
  }

  private SessionFactory fabrica() {
    var registro =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", "lexia_app")
            .applySetting("hibernate.connection.password", "")
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();
    return new MetadataSources(registro)
        .addAnnotatedClass(NotaryRemision.class)
        .addAnnotatedClass(NotaryRemisionVersion.class)
        .addAnnotatedClass(NotaryRemisionDocument.class)
        .addAnnotatedClass(NotaryEscritura.class)
        .buildMetadata()
        .buildSessionFactory();
  }

  /** Dos ediciones leen la misma row_version: la segunda debe fallar antes de numerar la versión. */
  @Test
  void rowVersionRechazaLaSegundaEdicionConcurrente() throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection db = conectar();
        PreparedStatement version =
            db.prepareStatement(
                """
                INSERT INTO control.notary_remision_version (remision_id, version_no, payload)
                VALUES (?, 1, '{}'::jsonb)
                """)) {
      sembrarRemision(db, id);
      version.setObject(1, id);
      version.executeUpdate();
    }
    try (SessionFactory fabrica = fabrica();
        Session primera = fabrica.openSession();
        Session segunda = fabrica.openSession()) {
      var transaccion = primera.beginTransaction();
      segunda.beginTransaction();
      NotaryRemision enPrimera = primera.find(NotaryRemision.class, id);
      NotaryRemision enSegunda = segunda.find(NotaryRemision.class, id);

      enPrimera.reenviar("primera");
      primera.flush();
      transaccion.commit();

      enSegunda.reenviar("segunda");
      assertThrows(PersistenceException.class, segunda::flush);
      segunda.getTransaction().rollback();
    }
  }

  /** La app arranca con ddl-auto=validate: un mapeo incorrecto la tumbaría al iniciar. */
  @Test
  void lasEntidadesCalzanYLexiaAppCreaLaVersion() throws Exception {
    UUID remision = UUID.randomUUID();
    try (Connection db = conectar()) {
      sembrarRemision(db, remision);
    }
    try (SessionFactory fabrica = fabrica()) {
      fabrica.inTransaction(
          sesion ->
              sesion.merge(
                  NotaryRemisionVersion.of(
                      UUID.randomUUID(),
                      remision,
                      1,
                      "{\"nombre_vendedor\":\"ACME\"}",
                      new NotaryRemisionVersion.Archivo(UUID.randomUUID(), "/ruta/m.docx", "m.docx"),
                      null,
                      null,
                      null,
                      "VIV_HIPOTECADA_BIESS")));
    }
    assertEquals(
        1,
        contar(
            """
            SELECT count(*) FROM control.notary_remision_version
            WHERE remision_id = ? AND payload ->> 'nombre_vendedor' = 'ACME'
            """,
            remision));
  }

  @Test
  void lexiaAppInsertaYLeePeroNoModifica() throws Exception {
    assertThat(privilegio("lexia_app", "SELECT")).isTrue();
    assertThat(privilegio("lexia_app", "INSERT")).isTrue();
    assertThat(privilegio("lexia_app", "UPDATE")).isFalse();
    assertThat(privilegio("lexia_app", "DELETE")).isFalse();
    assertThat(privilegio("lexia_readonly", "SELECT")).isTrue();
    assertThat(privilegio("lexia_readonly", "INSERT")).isFalse();
  }
}
