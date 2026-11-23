package com.lexia.api.modules.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RlsConnectionProvider implements MultiTenantConnectionProvider<String> {

  private final DataSource dataSource;

  public RlsConnectionProvider(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Connection getAnyConnection() throws SQLException {
    return dataSource.getConnection();
  }

  @Override
  public void releaseAnyConnection(Connection connection) throws SQLException {
    applyTenant(connection, TenantContext.UNSET);
    connection.close();
  }

  @Override
  public Connection getConnection(String tenantIdentifier) throws SQLException {
    Connection connection = dataSource.getConnection();
    applyTenant(connection, tenantIdentifier);
    return connection;
  }

  @Override
  public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
    applyTenant(connection, TenantContext.UNSET);
    connection.close();
  }

  @Override
  public boolean supportsAggressiveRelease() {
    return false;
  }

  @Override
  public boolean isUnwrappableAs(Class<?> unwrapType) {
    return unwrapType.isInstance(this);
  }

  @Override
  public <T> T unwrap(Class<T> unwrapType) {
    return unwrapType.cast(this);
  }

  /** Fija el tenant del hilo. Hibernate lo aplica en la conexión con {@code set_config}. */
  public static void setTenantId(UUID tenantId) {
    if (tenantId == null) {
      TenantContext.clear();
    } else {
      TenantContext.setTenantId(tenantId);
    }
  }

  static void applyTenant(Connection connection, String tenantIdentifier) throws SQLException {
    String value = tenantIdentifier == null ? TenantContext.UNSET : tenantIdentifier;
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, false)")) {
      statement.setString(1, value);
      statement.execute();
    }
  }
}
