package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.AttentionItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.GateItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.ValidationItem;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceServiceFormatoTest {

  private static ValidationItem validacion(String resultado, String codigo) {
    return new ValidationItem(
        UUID.randomUUID(), "Vigencia de certificados", "Regla", resultado, "—", "—", "—", codigo, false);
  }

  private static GateItem gate(String resultCode) {
    return new GateItem(UUID.randomUUID(), "G-001", "¿Título conforme?", "—", resultCode);
  }

  @Test
  void pesoSinArchivoNoSeInventa() {
    assertNull(WorkspaceService.formatSize(0));
    assertEquals("512 B", WorkspaceService.formatSize(512));
    assertEquals("850 KB", WorkspaceService.formatSize(850 * 1024));
    assertEquals("2.4 MB", WorkspaceService.formatSize(2_516_582));
  }

  @Test
  void mimeConocidoDaEtiquetaCorta() {
    assertEquals("PDF", WorkspaceService.mimeLabel("application/pdf"));
    assertEquals("JPG", WorkspaceService.mimeLabel("IMAGE/JPEG"));
    assertNull(WorkspaceService.mimeLabel("application/octet-stream"));
    assertNull(WorkspaceService.mimeLabel(null));
  }

  @Test
  void atencionPriorizaValidacionPendienteConSuCodigo() {
    AttentionItem item =
        WorkspaceService.buildAttention(
            List.of(validacion("Cumple", "V1"), validacion("No cumple", "V3")),
            List.of(gate("NOT_EVALUATED")),
            "Recepción",
            "Ana");
    assertEquals("V3", item.code());
    assertEquals("Revisar validación", item.nextAction());
  }

  @Test
  void atencionCaeAlGatePendiente() {
    AttentionItem item =
        WorkspaceService.buildAttention(
            List.of(validacion("Cumple", "V1"), validacion("No aplica", "V2")),
            List.of(gate("PASS"), gate("REVIEW_REQUIRED")),
            "Recepción",
            "Ana");
    assertEquals("G-001", item.code());
    assertEquals("Resolver gate", item.nextAction());
  }

  @Test
  void v4PendienteEnciendeLaAtencionConSuCodigo() {
    AttentionItem item =
        WorkspaceService.buildAttention(
            List.of(validacion("Cumple", "V1"), validacion("Pendiente", "V4")),
            List.of(gate("PASS")),
            "Recepción",
            "Ana");
    assertEquals("V4", item.code());
    assertEquals("Requiere atención", item.headline());
  }

  @Test
  void sinPendientesNoHayCodigo() {
    AttentionItem item =
        WorkspaceService.buildAttention(
            List.of(validacion("Cumple", "V1")), List.of(gate("PASS")), "Recepción", "Ana");
    assertNull(item.code());
    assertEquals("En curso", item.headline());
  }
}
