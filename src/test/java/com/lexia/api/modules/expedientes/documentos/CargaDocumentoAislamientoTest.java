package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/** S2 (borrador de otro tenant en memoria) y S3 (MIME y firma al subir). */
class CargaDocumentoAislamientoTest {

  private static final UUID TENANT_A = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID TENANT_B = UUID.fromString("b2000000-0000-7000-8000-000000000002");

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  @Test
  @SuppressWarnings("unchecked")
  void borradorEnMemoriaDeOtroTenantNoSeDevuelve() {
    CargaDocumentoService service =
        new CargaDocumentoService(
            null, null, null, null, null, mock(CaseDocumentoStore.class), mock(ObjectProvider.class));
    Map<String, CargaDocumentoService.DraftExpediente> drafts =
        (Map<String, CargaDocumentoService.DraftExpediente>)
            ReflectionTestUtils.getField(service, "drafts");
    String id = UUID.randomUUID().toString();
    CargaDocumentoService.DraftExpediente draft = new CargaDocumentoService.DraftExpediente(id, "ACTO");
    draft.asignarTenant(TENANT_A);
    drafts.put(id, draft);

    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), null, TENANT_B, null));
    ApiException ex = assertThrows(ApiException.class, () -> service.documentosParaProcesar(id));
    assertEquals(404, ex.getStatus().value());

    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), null, TENANT_A, null));
    assertDoesNotThrow(() -> service.documentosParaProcesar(id));
  }

  @Test
  void mimeSaleDeLaExtensionNoDelCliente() {
    assertEquals("application/pdf", CargaDocumentoService.mimeDeExtension("pdf"));
    assertEquals("image/jpeg", CargaDocumentoService.mimeDeExtension("jpeg"));
    assertEquals("image/tiff", CargaDocumentoService.mimeDeExtension("tif"));
    assertEquals("application/octet-stream", CargaDocumentoService.mimeDeExtension("html"));
  }

  @Test
  void firmaDebeCorresponderALaExtension() {
    byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
    assertThrows(ApiException.class, () -> CargaDocumentoService.exigirFirma("png", html));
    assertThrows(ApiException.class, () -> CargaDocumentoService.exigirFirma("pdf", html));
    assertThrows(ApiException.class, () -> CargaDocumentoService.exigirFirma("jpg", new byte[0]));

    assertDoesNotThrow(() -> CargaDocumentoService.exigirFirma("pdf", "%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1)));
    assertDoesNotThrow(
        () -> CargaDocumentoService.exigirFirma("png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}));
    assertDoesNotThrow(() -> CargaDocumentoService.exigirFirma("jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
    assertDoesNotThrow(() -> CargaDocumentoService.exigirFirma("tiff", new byte[] {'M', 'M', 0x00, 0x2A}));
  }
}
