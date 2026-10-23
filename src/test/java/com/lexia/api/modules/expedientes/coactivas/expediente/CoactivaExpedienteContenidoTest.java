package com.lexia.api.modules.expedientes.coactivas.expediente;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService.ArchivoContenido;
import com.lexia.api.modules.identity.AuthorizationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoactivaExpedienteContenidoTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final UUID EXPEDIENTE = UUID.fromString("d1000000-0000-7000-8000-000000000001");

  @Mock private CoactivaArchivoRepository archivos;
  @Mock private CoactivaArchivoStorage storage;
  @Mock private AuthorizationService authorization;

  private CoactivaExpedienteService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USER, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    service =
        new CoactivaExpedienteService(
            null, null, null, null, null, archivos, storage, null, null, null, authorization,
            null, null, null, null, null, null);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void archivoEnDiscoSeDevuelveTalCual() {
    CoactivaArchivo original = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "original.pdf");
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(original.getId(), TENANT)).thenReturn(Optional.of(original));
    when(storage.disponible(original)).thenReturn(true);
    when(storage.read(original)).thenReturn(new byte[] {1, 2, 3});

    ArchivoContenido contenido = service.contenido(original.getId());

    assertEquals("original.pdf", contenido.nombre());
    assertArrayEquals(new byte[] {1, 2, 3}, contenido.bytes());
    verify(archivos, never())
        .findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(TENANT, EXPEDIENTE);
  }

  @Test
  void sinOriginalDevuelveElUnificadoQueSiEstaEnDisco() {
    CoactivaArchivo original = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "original.pdf");
    CoactivaArchivo unificado = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "unificado.pdf");
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(original.getId(), TENANT)).thenReturn(Optional.of(original));
    when(storage.disponible(original)).thenReturn(false);
    when(archivos.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(TENANT, EXPEDIENTE))
        .thenReturn(List.of(unificado, original));
    when(storage.disponible(unificado)).thenReturn(true);
    when(storage.read(unificado)).thenReturn(new byte[] {'%', 'P', 'D', 'F'});

    ArchivoContenido contenido = service.contenido(original.getId());

    assertEquals("unificado.pdf", contenido.nombre());
    assertEquals("application/pdf", contenido.mimeType());
    assertArrayEquals(new byte[] {'%', 'P', 'D', 'F'}, contenido.bytes());
    verify(storage, never()).read(original);
  }

  @Test
  void sinNadaEnDiscoDevuelvePdfDeAviso() {
    CoactivaArchivo original = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "original.pdf");
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(original.getId(), TENANT)).thenReturn(Optional.of(original));
    when(storage.disponible(original)).thenReturn(false);
    when(archivos.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(TENANT, EXPEDIENTE))
        .thenReturn(List.of(original));

    ArchivoContenido contenido = service.contenido(original.getId());

    String pdf = new String(contenido.bytes(), StandardCharsets.US_ASCII);
    assertEquals("expediente-no-disponible.pdf", contenido.nombre());
    assertEquals("application/pdf", contenido.mimeType());
    assertTrue(pdf.startsWith("%PDF-1.4\n"));
    assertTrue(pdf.contains("El archivo original no esta en el servidor."));
    assertTrue(pdf.contains("startxref\n"));
    verify(storage, never()).read(original);
  }

  private static CoactivaArchivo archivo(String tipo, String nombre) {
    CoactivaArchivo a =
        CoactivaArchivo.create(
            UUID.randomUUID(), TENANT, tipo, nombre, "application/pdf", 10, "sha", "ruta", USER);
    a.vincularExpediente(EXPEDIENTE);
    return a;
  }
}
