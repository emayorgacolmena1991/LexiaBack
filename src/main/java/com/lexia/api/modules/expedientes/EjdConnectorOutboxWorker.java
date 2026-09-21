package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.tenancy.TenantBinder;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
@ConditionalOnProperty(name = "lexia.integrations.ejd.stub-live", havingValue = "false")
public class EjdConnectorOutboxWorker {

  private static final Logger LOG = LoggerFactory.getLogger(EjdConnectorOutboxWorker.class);

  private final OutboxEventRepository outboxEvents;
  private final EjdConnectorOutboxProcessor processor;
  private final EjdIntegrationProperties properties;
  private final ObjectProvider<TenantBinder> tenantBinder;

  public EjdConnectorOutboxWorker(
      OutboxEventRepository outboxEvents,
      EjdConnectorOutboxProcessor processor,
      EjdIntegrationProperties properties,
      ObjectProvider<TenantBinder> tenantBinder) {
    this.outboxEvents = outboxEvents;
    this.processor = processor;
    this.properties = properties;
    this.tenantBinder = tenantBinder;
  }

  @Scheduled(fixedDelayString = "${lexia.integrations.ejd.worker.poll-interval-ms:5000}")
  public void pollOutbox() {
    List<OutboxEvent> pending =
        outboxEvents.findTop50ByPublishedAtIsNullAndEventTypeOrderByCreatedAtAsc(
            EjdConnectorOutboxProcessor.OUTBOX_TYPE);
    int limit = Math.max(1, properties.getWorker().getBatchSize());
    int processed = 0;
    for (OutboxEvent event : pending) {
      if (processed >= limit) {
        break;
      }
      try {
        tenantBinder.ifAvailable(binder -> binder.bind(event.getTenantId()));
        processor.process(event.getId());
        processed++;
      } catch (RuntimeException ex) {
        LOG.error("Outbox processing failed for {}", event.getId(), ex);
      } finally {
        TenantContext.clear();
      }
    }
  }
}
