package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.reglas.ValidacionIaService;
import com.lexia.api.modules.ia.ocr.DocumentReuploadController;
import com.lexia.api.modules.ia.ocr.OcrAzureBatchService;
import com.lexia.api.modules.identity.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class CargaDocumentoPermisosTest {

  private static final String CASO = "11111111-2222-3333-4444-555555555555";

  private final CargaDocumentoService carga = mock(CargaDocumentoService.class);
  private final OcrAzureBatchService ocr = mock(OcrAzureBatchService.class);
  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final MockMultipartFile archivo =
      new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1});
  private CargaDocumentoController controller;
  private DocumentReuploadController reupload;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ObjectProvider<AuthorizationService> provider = mock(ObjectProvider.class, CALLS_REAL_METHODS);
    doReturn(authorization).when(provider).getIfAvailable();
    controller = new CargaDocumentoController(carga, mock(ValidacionIaService.class), provider);
    reupload = new DocumentReuploadController(ocr, provider);
    doThrow(new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sin permiso"))
        .when(authorization)
        .requirePermission("expedientes:caso:escribir");
  }

  private void assertProhibido(Executable accion) {
    AuthException error = assertThrows(AuthException.class, accion);
    assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
  }

  @Test
  void sinPermisoNoSeSubeNiSeClasificaNiSeElimina() {
    assertProhibido(() -> controller.subir(CASO, archivo));
    assertProhibido(
        () -> controller.actualizarTipo(CASO, "d1", new ActualizarTipoRequest("CEDULA")));
    assertProhibido(() -> controller.eliminar(CASO, "d1"));
    verifyNoInteractions(carga);
  }

  @Test
  void sinPermisoNoSeReemplaza() {
    assertProhibido(() -> reupload.reupload(CASO, "d1", "CEDULA", archivo));
    verifyNoInteractions(ocr);
  }

  @Test
  void conPermisoLlegaAlServicio() {
    doThrow(new IllegalStateException("alcanzado")).when(carga).subir(any(), any());
    org.mockito.Mockito.reset(authorization);
    assertThrows(IllegalStateException.class, () -> controller.subir(CASO, archivo));
  }
}
