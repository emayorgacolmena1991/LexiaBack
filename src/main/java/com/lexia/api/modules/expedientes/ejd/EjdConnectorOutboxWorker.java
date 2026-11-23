package com.lexia.api.modules.expedientes.ejd;

import com.lexia.api.modules.tenancy.TenantBinder;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
@ConditionalOnProperty(name = "lexia.integrations.ejd.stub-live", havingValue = "false")
public class EjdConnectorOutboxWorker {

  private static final Logger LOG = LoggerFactory.getLogger(EjdConnectorOutboxWorker.class);

  /**
   * Sin tenant, RLS oculta app.outbox_event: la función (SECURITY DEFINER, V107) lista los
   * pendientes de todos los tenants y solo devuelve id y tenant.
   */
  static final String SQL_PENDIENTES = "SELECT id, tenant_id FROM app.outbox_pendientes(?, ?)";

  private final JdbcTemplate jdbc;
  private final EjdConnectorOutboxProcessor processor;
  private final EjdIntegrationProperties properties;
  private final ObjectProvider<TenantBinder> tenantBinder;

  public EjdConnectorOutboxWorker(
      JdbcTemplate jdbc,
      EjdConnectorOutboxProcessor processor,
      EjdIntegrationProperties properties,
      ObjectProvider<TenantBinder> tenantBinder) {
    this.jdbc = jdbc;
    this.processor = processor;
    this.properties = properties;
    this.tenantBinder = tenantBinder;
  }

  @Scheduled(fixedDelayString = "${lexia.integrations.ejd.worker.poll-interval-ms:5000}")
  public void pollOutbox() {
    int limit = Math.max(1, properties.getWorker().getBatchSize());
    List<Pendiente> pendientes =
        jdbc.query(
            SQL_PENDIENTES,
            (rs, row) -> new Pendiente(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
            EjdConnectorOutboxProcessor.OUTBOX_TYPE,
            limit);
    for (Pendiente pendiente : pendientes) {
      try {
        // El procesado (transacción propia) toma el tenant del hilo: RLS sigue aplicando.
        TenantContext.setTenantId(pendiente.tenantId());
        tenantBinder.ifAvailable(binder -> binder.bind(pendiente.tenantId()));
        processor.process(pendiente.id());
      } catch (RuntimeException ex) {
        LOG.error(
            "Outbox processing failed for {} tenant={}", pendiente.id(), pendiente.tenantId(), ex);
      } finally {
        TenantContext.clear();
      }
    }
  }

  record Pendiente(UUID id, UUID tenantId) {}
}
