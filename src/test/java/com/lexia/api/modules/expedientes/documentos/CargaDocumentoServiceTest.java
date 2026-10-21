package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.actos.ActoNotarialService;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.BorradorResponse;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.CrearBorradorRequest;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class CargaDocumentoServiceTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final UUID MOCK_FALLBACK =
      UUID.fromString("f7158291-5fe9-42b7-a423-0712f315d611");

  @Mock private ActoNotarialService actos;
  @Mock private DocumentoTextoOcrRepository ocr;
  @Mock private ProductoBiessService productos;
  @Mock private ExpedienteBorradorStore borradores;
  @Mock private CaseDocumentoStore caseDocs;

  private CargaDocumentoService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USER, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    service = new CargaDocumentoService(actos, null, ocr, productos, borradores, caseDocs);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void crearBorradorDevuelveUuidDePostgres() {
    UUID generated = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    when(borradores.insert(TENANT, null, "VIV_HIPOTECADA_BIESS", "DAULE", "DIGITAL_SEPARADO", USER))
        .thenReturn(generated);

    BorradorResponse response =
        service.crearBorrador(new CrearBorradorRequest(null, "VIV_HIPOTECADA_BIESS", "DAULE"));

    assertEquals(generated.toString(), response.id());
    assertEquals(generated.toString(), response.idExpediente());
    assertEquals("BORRADOR", response.estado());
    verify(productos).requireProducto(TENANT, "VIV_HIPOTECADA_BIESS");
  }

  @Test
  void subirConUuidInexistenteResponde404() {
    when(borradores.find(MOCK_FALLBACK, TENANT)).thenReturn(Optional.empty());
    MockMultipartFile file =
        new MockMultipartFile("file", "cedula.pdf", "application/pdf", new byte[] {1, 2});

    ApiException error =
        assertThrows(ApiException.class, () -> service.subir(MOCK_FALLBACK.toString(), file));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    assertEquals("NOT_FOUND", error.getCode());
  }

  @Test
  void subirAdjuntaArchivoSiElBorradorEstaEnPostgres() {
    UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
    when(borradores.find(id, TENANT))
        .thenReturn(
            Optional.of(
                ExpedienteBorrador.loaded(
                    id, TENANT, null, "VIV_HIPOTECADA_BIESS", "DAULE", "DIGITAL_SEPARADO")));
    MockMultipartFile file =
        new MockMultipartFile("file", "cedula.pdf", "application/pdf", new byte[] {1, 2, 3});

    var dto = service.subir(id.toString(), file);

    assertEquals("cedula.pdf", dto.nombreOriginal());
  }
}
