package com.lexia.api.modules.expedientes.coactivas.expediente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.AnalisisResponse;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaValidacionIaWorker;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class CoactivaExpedienteReanalisisTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-000000000001");

  @Mock private CoactivaExpedienteRepository expedientes;
  @Mock private CoactivaEventoRepository eventos;
  @Mock private CoactivaArchivoRepository archivos;
  @Mock private AuthorizationService authorization;
  @Mock private CoactivaValidacionIaWorker validacionIa;
  @Mock private CoactivaExpedienteAnalisisWorker diagnostico;

  private CoactivaExpedienteService service;
  private CoactivaExpediente expediente;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USER, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    service =
        new CoactivaExpedienteService(
            expedientes, null, null, null, eventos, archivos, null, null, null, null, authorization,
            validacionIa, diagnostico, null, null);
    expediente = CoactivaExpediente.create(TENANT, UUID.randomUUID(), "025-2024-00026", USER);
    when(expedientes.findByIdAndTenantIdAndDeletedAtIsNull(expediente.getId(), TENANT))
        .thenReturn(Optional.of(expediente));
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void reanalizaElPdfDelUltimoAnalisisYRegistraElEvento() {
    CoactivaArchivo pdf = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO);
    expediente.marcarAnalizando(pdf.getId());
    expediente.marcarAnalizado();
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(pdf.getId(), TENANT)).thenReturn(Optional.of(pdf));

    AnalisisResponse r = service.reanalizar(expediente.getId());

    assertEquals("ANALIZANDO", r.estadoAnalisis());
    assertEquals(pdf.getId(), r.archivoId());
    assertTrue(pdf.analizando());
    assertEquals(CoactivaExpediente.ANALISIS_ANALIZANDO, expediente.getEstadoAnalisis());
    verify(authorization).requirePermission(CoactivaPermisos.ESCRIBIR);
    verify(diagnostico).analizarAsync(TENANT, pdf.getId());
    verify(validacionIa, never()).analizarAsync(any(), any());
    ArgumentCaptor<CoactivaEvento> evento = ArgumentCaptor.forClass(CoactivaEvento.class);
    verify(eventos).save(evento.capture());
    assertEquals("ANALISIS_REINICIADO", evento.getValue().getTipo());
    assertEquals(pdf.getId(), evento.getValue().getArchivoId());
  }

  @Test
  void sinAnalisisPrevioUsaElUltimoExpedienteEscaneado() {
    CoactivaArchivo acta = archivo(CoactivaArchivo.ACTA);
    CoactivaArchivo escaneado = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO);
    when(archivos.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(TENANT, expediente.getId()))
        .thenReturn(List.of(acta, escaneado));

    AnalisisResponse r = service.reanalizar(expediente.getId());

    assertEquals(escaneado.getId(), r.archivoId());
    verify(diagnostico).analizarAsync(TENANT, escaneado.getId());
  }

  @Test
  void enAnalisisResponde409() {
    expediente.marcarAnalizando(UUID.randomUUID());

    ApiException e = assertThrows(ApiException.class, () -> service.reanalizar(expediente.getId()));

    assertEquals(HttpStatus.CONFLICT, e.getStatus());
    verify(diagnostico, never()).analizarAsync(any(), any());
    verify(eventos, never()).save(any());
  }

  @Test
  void sinPdfResponde404() {
    when(archivos.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(TENANT, expediente.getId()))
        .thenReturn(List.of(archivo(CoactivaArchivo.ACTA)));

    ApiException e = assertThrows(ApiException.class, () -> service.reanalizar(expediente.getId()));

    assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    verify(diagnostico, never()).analizarAsync(any(), any());
  }

  private CoactivaArchivo archivo(String tipo) {
    CoactivaArchivo a =
        CoactivaArchivo.create(
            UUID.randomUUID(), TENANT, tipo, tipo + ".pdf", "application/pdf", 10, "sha", "ruta", USER);
    a.vincularExpediente(expediente.getId());
    return a;
  }
}
