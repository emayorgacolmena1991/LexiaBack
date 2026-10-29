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
        "Flyway initialized. Schema version check enabled.");
  }
}
