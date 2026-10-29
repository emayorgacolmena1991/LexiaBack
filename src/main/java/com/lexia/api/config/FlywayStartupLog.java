package com.lexia.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class FlywayStartupLog {

  private static final Logger LOG = LoggerFactory.getLogger(FlywayStartupLog.class);

  @EventListener(ApplicationReadyEvent.class)
  public void verificarFlyway() {
    LOG.info(
        "Flyway config: ignore-migration-patterns=*:missing activo. "
            + "Verifica log de arranque: 'Current version: 87' + 'Migrating to 88' o 'up to date'.");
  }
}
