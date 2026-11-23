package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CargaDocumentoContenidoTest {

  @Test
  void soloSirveTiposConocidos() {
    assertEquals(MediaType.APPLICATION_PDF, CargaDocumentoController.tipoSeguro("APPLICATION/PDF"));
    assertEquals(MediaType.IMAGE_PNG, CargaDocumentoController.tipoSeguro("image/png"));
  }

  @Test
  void mimeDeclaradoPorElClienteNoPuedeActivarHtml() {
    assertEquals(MediaType.APPLICATION_OCTET_STREAM, CargaDocumentoController.tipoSeguro("text/html"));
    assertEquals(MediaType.APPLICATION_OCTET_STREAM, CargaDocumentoController.tipoSeguro(null));
  }
}
