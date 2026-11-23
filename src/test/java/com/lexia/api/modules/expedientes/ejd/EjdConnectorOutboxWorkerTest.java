package com.lexia.api.modules.expedientes.ejd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.expedientes.ejd.EjdConnectorOutboxWorker.Pendiente;
import com.lexia.api.modules.tenancy.TenantBinder;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class EjdConnectorOutboxWorkerTest {

  private static final UUID TENANT_A = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID TENANT_B = UUID.fromString("b2000000-0000-7000-8000-000000000002");
  private static final UUID EVENTO_A = UUID.randomUUID();
  private static final UUID EVENTO_B = UUID.randomUUID();

  private JdbcTemplate jdbc;
  private EjdConnectorOutboxProcessor processor;
  private EjdConnectorOutboxWorker worker;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    processor = mock(EjdConnectorOutboxProcessor.class);
    EjdIntegrationProperties properties = new EjdIntegrationProperties();
    properties.getWorker().setBatchSize(50);
    ObjectProvider<TenantBinder> binder = mock(ObjectProvider.class);
    worker = new EjdConnectorOutboxWorker(jdbc, processor, properties, binder);
    when(jdbc.query(
            eq(EjdConnectorOutboxWorker.SQL_PENDIENTES),
            any(RowMapper.class),
            eq(EjdConnectorOutboxProcessor.OUTBOX_TYPE),
            eq(50)))
        .thenReturn(List.of(new Pendiente(EVENTO_A, TENANT_A), new Pendiente(EVENTO_B, TENANT_B)));
  }

  @AfterEach
  void limpiar() {
    TenantContext.clear();
  }

  @Test
  void procesaPendientesDeVariosTenantsConElTenantDeCadaEvento() {
    List<UUID> tenantsVistos = new ArrayList<>();
    doAnswer(
            inv -> {
              tenantsVistos.add(TenantContext.getTenantId());
              return null;
            })
        .when(processor)
        .process(any());

    worker.pollOutbox();

    verify(processor).process(EVENTO_A);
    verify(processor).process(EVENTO_B);
    assertEquals(List.of(TENANT_A, TENANT_B), tenantsVistos);
    assertNull(TenantContext.getTenantId());
  }

  @Test
  void unFalloNoDetieneLosDemasEventos() {
    doAnswer(
            inv -> {
              throw new IllegalStateException("conector caído");
            })
        .when(processor)
        .process(EVENTO_A);

    worker.pollOutbox();

    verify(processor).process(EVENTO_B);
    assertNull(TenantContext.getTenantId());
  }
}
