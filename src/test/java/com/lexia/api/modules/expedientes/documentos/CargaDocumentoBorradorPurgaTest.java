package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class CargaDocumentoBorradorPurgaTest {

  @Test
  @SuppressWarnings("unchecked")
  void soloSalenLosBorradoresSinUsoMasAllaDelTtl() {
    CargaDocumentoService service =
        new CargaDocumentoService(null, null, null, null, null, null, mock(ObjectProvider.class));
    Map<String, CargaDocumentoService.DraftExpediente> drafts =
        (Map<String, CargaDocumentoService.DraftExpediente>)
            ReflectionTestUtils.getField(service, "drafts");
    CargaDocumentoService.DraftExpediente viejo =
        new CargaDocumentoService.DraftExpediente("viejo", "ACTO");
    CargaDocumentoService.DraftExpediente reciente =
        new CargaDocumentoService.DraftExpediente("reciente", "ACTO");
    long ahora = System.currentTimeMillis();
    ReflectionTestUtils.setField(viejo, "ultimoUsoMs", ahora - 25L * 3_600_000L);
    drafts.put("viejo", viejo);
    drafts.put("reciente", reciente);

    int purgados = service.purgarBorradoresInactivos(ahora);

    assertEquals(1, purgados);
    assertTrue(drafts.containsKey("reciente"));
  }
}
