package com.lexia.api.modules.notary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * V101: {@code storage_key} del snapshot con backfill desde {@code app.document_version}. Postgres 16
 * embebido, nunca la base remota: {@code mvnw test -Dtest=NotaryRemisionDocumentStorageKeyMigrationIT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotaryRemisionDocumentStorageKeyMigrationIT {

  private static final String ANTERIOR = "100";
  private static final String NUEVA = "101";
  private static final String BUFETE = "b1000000-0000-7000-8000-000000000001";
  private static final String NOTARIA = "b1000000-0000-7000-8000-000000000002";
  private static final String CLAVE = "C:\\lexia\\data\\expedientes\\" + BUFETE + "\\caso\\doc_cedula.pdf";

  private final UUID conVersion = UUID.randomUUID();
  private final UUID versionInexistente = UUID.randomUUID();
  private final UUID versionDeOtroTenant = UUID.randomUUID();

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
      assertEquals(ANTERIOR, versionAplicada(db));
      sembrar(db);
    }
    flyway("latest").migrate();
  }

  @AfterAll
  void cerrar() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  /**
   * Una remisión con tres filas: versión del bufete con clave (se copia), versión que no existe y
   * versión de otro tenant (ambas quedan NULL).
   */
  private void sembrar(Connection db) throws SQLException {
    UUID caso = UUID.randomUUID();
    UUID documento = UUID.randomUUID();
    UUID remision = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    ejecutar(
        db,
        """
        INSERT INTO app.legal_case (id, tenant_id, code, case_type, vertical, subject)
        VALUES (?, ?::uuid, 'LEX-IT-V101', 'EJD', 'ESCRITURACION', 'Compraventa')
        """,
        caso,
        BUFETE);
    ejecutar(
        db,
        "INSERT INTO app.document (id, tenant_id, case_id, name) VALUES (?, ?::uuid, ?, 'cedula.pdf')",
        documento,
        BUFETE,
        caso);
    ejecutar(
        db,
        """
        INSERT INTO app.document_version
            (id, tenant_id, document_id, version_no, origin, mime_type, content_hash, storage_key)
        VALUES (?, ?::uuid, ?, 1, 'UPLOAD', 'application/pdf', 'sha', ?)
        """,
        conVersion,
        BUFETE,
        documento,
        CLAVE);
    ejecutar(
        db,
        """
        INSERT INTO control.notary_remision (
            id, firm_tenant_id, notary_tenant_id, firm_legal_case_id, expediente_codigo)
        VALUES (?, ?::uuid, ?::uuid, ?, 'LEX-IT-V101')
        """,
        remision,
        BUFETE,
        NOTARIA,
        caso);
    ejecutar(
        db,
        """
        INSERT INTO control.notary_remision_version (id, remision_id, version_no, payload)
        VALUES (?, ?, 1, '{}'::jsonb)
        """,
        version,
        remision);
    fila(db, remision, version, BUFETE, conVersion, 0);
    fila(db, remision, version, BUFETE, versionInexistente, 1);
    // Mismo id de versión del bufete, pero la fila declara otro tenant: no debe copiarse.
    fila(db, remision, version, NOTARIA, versionDeOtroTenant(db, documento), 2);
  }

  /** Versión real del bufete referenciada desde una fila con firm_tenant_id de la notaría. */
  private UUID versionDeOtroTenant(Connection db, UUID documento) throws SQLException {
    ejecutar(
        db,
        """
        INSERT INTO app.document_version
            (id, tenant_id, document_id, version_no, origin, content_hash, storage_key)
        VALUES (?, ?::uuid, ?, 2, 'UPLOAD', 'sha2', 'otra/clave.pdf')
        """,
        versionDeOtroTenant,
        BUFETE,
        documento);
    return versionDeOtroTenant;
  }

  private static void fila(
      Connection db, UUID remision, UUID version, String tenant, UUID documentVersion, int orden)
      throws SQLException {
    ejecutar(
        db,
        """
        INSERT INTO control.notary_remision_document (
            remision_id, version_id, firm_tenant_id, firm_document_version_id, display_name, sort_order)
        VALUES (?, ?, ?::uuid, ?, ?, ?)
        """,
        remision,
        version,
        tenant,
        documentVersion,
        "doc" + orden + ".pdf",
        orden);
  }

  private static void ejecutar(Connection db, String sql, Object... parametros) throws SQLException {
    try (PreparedStatement ps = db.prepareStatement(sql)) {
      for (int i = 0; i < parametros.length; i++) {
        ps.setObject(i + 1, parametros[i]);
      }
      ps.executeUpdate();
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

  private String claveDeLaFila(UUID firmDocumentVersionId) throws SQLException {
    try (Connection db = conectar();
        PreparedStatement ps =
            db.prepareStatement(
                """
                SELECT storage_key FROM control.notary_remision_document
                WHERE firm_document_version_id = ?
                """)) {
      ps.setObject(1, firmDocumentVersionId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  @Test
  void aplicoLaMigracion() throws Exception {
    try (Connection db = conectar();
        PreparedStatement ps =
            db.prepareStatement(
                "SELECT count(*) FROM public.flyway_schema_history WHERE version = ? AND success")) {
      ps.setString(1, NUEVA);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals(1, rs.getLong(1));
      }
    }
  }

  @Test
  void backfillCopiaLaClaveDeLaVersionEnviada() throws Exception {
    assertEquals(CLAVE, claveDeLaFila(conVersion));
  }

  @Test
  void sinVersionOConOtroTenantQuedaNullParaElFallback() throws Exception {
    assertNull(claveDeLaFila(versionInexistente));
    assertNull(claveDeLaFila(versionDeOtroTenant));
  }

  @Test
  void storageKeyEsOpcional() throws Exception {
    try (Connection db = conectar();
        PreparedStatement ps =
            db.prepareStatement(
                """
                SELECT is_nullable, character_maximum_length FROM information_schema.columns
                WHERE table_schema = 'control' AND table_name = 'notary_remision_document'
                  AND column_name = 'storage_key'
                """)) {
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals("YES", rs.getString(1));
        assertEquals(512, rs.getInt(2));
      }
    }
  }

  /** La app arranca con ddl-auto=validate: el mapeo de la entidad debe calzar con la columna. */
  @Test
  void laEntidadCalzaConLaTabla() {
    var registro =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", "lexia_app")
            .applySetting("hibernate.connection.password", "")
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();
    try (SessionFactory fabrica =
        new MetadataSources(registro)
            .addAnnotatedClass(NotaryRemisionDocument.class)
            .buildMetadata()
            .buildSessionFactory()) {
      assertTrue(fabrica.isOpen());
    }
  }
}
