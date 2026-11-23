package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaCatalog.Entrada;
import com.lexia.api.modules.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Siembra idempotente de {@code app.coactiva_plantilla} por tenant.
 * {@code ON CONFLICT DO NOTHING}: no reactiva ni pisa ediciones del tenant.
 */
@Component
public class CoactivaPlantillaCatalogSeeder {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaPlantillaCatalogSeeder.class);

  private static final String INSERT_SQL =
      """
      INSERT INTO app.coactiva_plantilla (tenant_id, codigo, nombre, etapa, ruta_docx)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (tenant_id, codigo) DO NOTHING
      """;

  private final JdbcTemplate jdbc;

  public CoactivaPlantillaCatalogSeeder(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Siembra el catálogo canónico para un tenant. Seguro llamar varias veces. */
  public int sembrar(UUID tenantId) {
    if (tenantId == null) {
      return 0;
    }
    UUID previo = TenantContext.getTenantId();
    TenantContext.setTenantId(tenantId);
    try {
      Integer inserted =
          jdbc.execute(
              (ConnectionCallback<Integer>)
                  connection -> {
                    bindTenant(connection, tenantId);
                    return sembrar(
                        tenantId,
                        CoactivaPlantillaCatalog.ENTRADAS,
                        ruta -> new ClassPathResource(ruta).exists(),
                        (t, e) -> insertIgnoreConflict(connection, t, e));
                  });
      return inserted == null ? 0 : inserted;
    } finally {
      if (previo == null) {
        TenantContext.clear();
      } else {
        TenantContext.setTenantId(previo);
      }
    }
  }

  /**
   * Núcleo testeable: inserta solo entradas cuya {@code rutaDocx} existe; nunca lanza por ruta
   * faltante.
   */
  int sembrar(
      UUID tenantId,
      List<Entrada> catalogo,
      Predicate<String> recursoExiste,
      Insertor insertor)
      throws SQLException {
    int insertadas = 0;
    for (Entrada entrada : catalogo) {
      if (!recursoExiste.test(entrada.rutaDocx())) {
        LOG.warn(
            "Plantilla coactiva omitida (ruta_docx ausente en classpath): codigo={} ruta={}",
            entrada.codigo(),
            entrada.rutaDocx());
        continue;
      }
      if (insertor.insert(tenantId, entrada)) {
        insertadas++;
      }
    }
    return insertadas;
  }

  @FunctionalInterface
  interface Insertor {
    /** @return true si la fila se insertó (false si ya existía / DO NOTHING). */
    boolean insert(UUID tenantId, Entrada entrada) throws SQLException;
  }

  private static boolean insertIgnoreConflict(Connection connection, UUID tenantId, Entrada e)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
      ps.setObject(1, tenantId);
      ps.setString(2, e.codigo());
      ps.setString(3, e.nombre());
      ps.setString(4, e.etapa());
      ps.setString(5, e.rutaDocx());
      return ps.executeUpdate() > 0;
    }
  }

  private static void bindTenant(Connection connection, UUID tenantId) throws SQLException {
    try (PreparedStatement set =
        connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
      set.setString(1, tenantId.toString());
      set.execute();
    }
  }
}
