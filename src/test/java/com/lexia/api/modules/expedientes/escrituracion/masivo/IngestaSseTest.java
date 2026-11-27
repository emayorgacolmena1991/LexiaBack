package com.lexia.api.modules.expedientes.escrituracion.masivo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.jobs.BatchEventPublisherService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IngestaSseTest {

  @Test
  void publicadorMantieneOyenteTrasAbrir() {
    ObjectMapper json = new ObjectMapper();
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    BatchEventPublisherService publisher =
        new BatchEventPublisherService(
            jdbc, "jdbc:postgresql://localhost/test", "u", "p", json);
    UUID lote = UUID.randomUUID();
    publisher.abrir(lote, 30L * 60L * 1000L);
    assertEquals(1, publisher.expedientesConOyentes());
  }
}