package com.lexia.api.modules.expedientes.coactivas.expediente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService.ArchivoContenido;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaAnalisis;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaAnalisisRepository;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaPdfTexto;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaPdfTexto.Coincidencia;
import com.lexia.api.modules.ia.ocr.DocumentoCacheStore;
import com.lexia.api.modules.ia.ocr.DocumentoCacheStore.Hit;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Las cajas tienen que ser del PDF que contenido() sirve. Si no, la búsqueda va vacía. */
@ExtendWith(MockitoExtension.class)
class CoactivaBuscarOcrVisorTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-0000000000b2");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-0000000000b2");
  private static final String OCR = "--- FOJA 2 ---\ntexto del garante\n";

  @Mock private CoactivaExpedienteRepository expedientes;
  @Mock private CoactivaArchivoRepository archivos;
  @Mock private CoactivaArchivoStorage storage;
  @Mock private AuthorizationService authorization;
  @Mock private CoactivaAnalisisRepository analisisRepo;
  @Mock private DocumentoCacheStore cache;

  private CoactivaExpedienteService service;
  private CoactivaExpediente expediente;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USER, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    service =
        new CoactivaExpedienteService(
            expedientes, null, null, null, null, archivos, storage, null, null, null, authorization,
            null, analisisRepo, null, null, null, cache);
    expediente = CoactivaExpediente.create(TENANT, UUID.randomUUID(), "025-2024-00026", USER);
    when(expedientes.findByIdAndTenantIdAndDeletedAtIsNull(expediente.getId(), TENANT))
        .thenReturn(Optional.of(expediente));
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void placeholderNoDevuelveCajasDelPuntero() {
    CoactivaArchivo pedido = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "original.pdf", "sha-original");
    expediente.marcarAnalizando(pedido.getId());
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(pedido.getId(), TENANT)).thenReturn(Optional.of(pedido));
    when(storage.disponible(pedido)).thenReturn(false);
    lenient()
        .when(cache.buscar(TENANT, "sha-original", CoactivaPdfTexto.VERSION_CACHE))
        .thenReturn(Optional.of(new Hit("AZURE_READ", OCR, null)));

    ArchivoContenido contenido = service.contenido(pedido.getId());
    List<Coincidencia> hits = service.buscarOcr(expediente.getId(), "garante");

    assertEquals("expediente-no-disponible.pdf", contenido.nombre());
    assertTrue(hits.isEmpty());
  }

  @Test
  void hermanoSinCacheNoDevuelveCajasDelPuntero() {
    CoactivaArchivo pedido = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "original.pdf", "sha-original");
    CoactivaArchivo hermano = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "unificado.pdf", "sha-unificado");
    expediente.marcarAnalizando(pedido.getId());
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(pedido.getId(), TENANT)).thenReturn(Optional.of(pedido));
    lenient().when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(hermano.getId(), TENANT)).thenReturn(Optional.of(hermano));
    lenient().when(storage.disponible(pedido)).thenReturn(false);
    lenient().when(storage.disponible(hermano)).thenReturn(true);
    lenient()
        .when(archivos.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(TENANT, expediente.getId()))
        .thenReturn(List.of(hermano, pedido));
    lenient()
        .when(cache.buscar(TENANT, "sha-original", CoactivaPdfTexto.VERSION_CACHE))
        .thenReturn(Optional.of(new Hit("AZURE_READ", OCR, null)));

    List<Coincidencia> hits = service.buscarOcr(expediente.getId(), "garante");

    assertTrue(hits.isEmpty());
  }

  @Test
  void archivoEnDiscoConCacheDevuelveCajas() {
    CoactivaArchivo pedido = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "unificado.pdf", "sha-unificado");
    expediente.marcarAnalizando(pedido.getId());
    expediente.marcarAnalizado();
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(pedido.getId(), TENANT)).thenReturn(Optional.of(pedido));
    lenient().when(storage.disponible(pedido)).thenReturn(true);
    lenient()
        .when(analisisRepo.findFirstByTenantIdAndExpedienteIdOrderByCreatedAtDesc(TENANT, expediente.getId()))
        .thenReturn(Optional.of(analisisDe(pedido)));
    when(cache.buscar(TENANT, "sha-unificado", CoactivaPdfTexto.VERSION_CACHE))
        .thenReturn(Optional.of(new Hit("AZURE_READ", OCR, null)));

    List<Coincidencia> hits = service.buscarOcr(expediente.getId(), "garante");

    assertEquals(1, hits.size());
    assertEquals(2, hits.get(0).foja());
  }

  @Test
  void unificadoVisibleSinCacheNoUsaElEscaneadoDelPuntero() {
    CoactivaArchivo escaneado = archivo(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "original.pdf", "sha-original");
    CoactivaArchivo unificado = archivo(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "unificado.pdf", "sha-unificado");
    expediente.marcarAnalizando(escaneado.getId());
    expediente.marcarAnalizado();
    lenient().when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(escaneado.getId(), TENANT)).thenReturn(Optional.of(escaneado));
    lenient().when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(unificado.getId(), TENANT)).thenReturn(Optional.of(unificado));
    lenient().when(storage.disponible(unificado)).thenReturn(true);
    lenient()
        .when(analisisRepo.findFirstByTenantIdAndExpedienteIdOrderByCreatedAtDesc(TENANT, expediente.getId()))
        .thenReturn(Optional.of(analisisDe(unificado)));
    lenient()
        .when(cache.buscar(TENANT, "sha-original", CoactivaPdfTexto.VERSION_CACHE))
        .thenReturn(Optional.of(new Hit("AZURE_READ", OCR, null)));

    List<Coincidencia> hits = service.buscarOcr(expediente.getId(), "garante");

    assertTrue(hits.isEmpty());
  }

  private CoactivaAnalisis analisisDe(CoactivaArchivo archivo) {
    return CoactivaAnalisis.consolidado(
        TENANT, expediente.getId(), archivo.getId(), null, "*", "{}", 0, null, null);
  }

  private CoactivaArchivo archivo(String tipo, String nombre, String sha) {
    CoactivaArchivo archivo =
        CoactivaArchivo.create(
            UUID.randomUUID(), TENANT, tipo, nombre, "application/pdf", 10, sha, "ruta/" + nombre, USER);
    archivo.vincularExpediente(expediente.getId());
    return archivo;
  }
}
