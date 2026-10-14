package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorrador;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OcrCotejoPersistenciaTest {

  @Mock private DocumentosExtraidosStore documentos;
  @Mock private ExpedienteBorradorStore borradores;
  @Mock private DocumentoTextoOcrRepository ocrRows;

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void reabrirConEstudioEnDbNoDeclaraSinExtraccion() {
    UUID tenant = UUID.randomUUID();
    UUID caso = UUID.randomUUID();
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), tenant, UUID.randomUUID()));
    ExpedienteBorrador row = mock(ExpedienteBorrador.class);
    when(row.getDatosExtraidos()).thenReturn("{\"resumen\":\"estudio\"}");
    when(documentos.cargar(caso, tenant)).thenReturn(List.of());
    when(borradores.find(caso, tenant)).thenReturn(Optional.of(row));
    when(ocrRows.findByIdExpedienteOrderByCreatedAtAsc(caso.toString())).thenReturn(List.of());

    CotejoResponse respuesta =
        new OcrCotejoService(documentos, borradores, ocrRows, new ObjectMapper())
            .cotejar(caso.toString(), null);

    assertNull(respuesta.estado());
  }

  @Test
  void sinFilasPersistidasSigueSiendoSinExtraccion() {
    UUID tenant = UUID.randomUUID();
    UUID caso = UUID.randomUUID();
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), tenant, UUID.randomUUID()));
    when(documentos.cargar(caso, tenant)).thenReturn(List.of());
    when(borradores.find(caso, tenant)).thenReturn(Optional.empty());
    when(ocrRows.findByIdExpedienteOrderByCreatedAtAsc(caso.toString())).thenReturn(List.of());

    CotejoResponse respuesta =
        new OcrCotejoService(documentos, borradores, ocrRows, new ObjectMapper())
            .cotejar(caso.toString(), null);

    assertEquals("SIN_EXTRACCION", respuesta.estado());
  }
}
