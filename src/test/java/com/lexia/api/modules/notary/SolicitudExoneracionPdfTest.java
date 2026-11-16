package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class SolicitudExoneracionPdfTest {

  private static ObjectNode payload() {
    ObjectNode payload = new ObjectMapper().createObjectNode();
    payload.put("nombre_conyuge_1", "LUIS MORA");
    payload.put("cedula_conyuge_1", "0912345678");
    payload.put("fecha_nacimiento_conyuge_1", "01/01/1990");
    payload.put("nombre_conyuge_2", "ANA PÉREZ ÑAUPARI");
    payload.put("cedula_conyuge_2", "0987654321");
    payload.put("fecha_nacimiento_conyuge_2", "15/03/1950");
    payload.put("clave_catastral", "12-0034-005-0-0-0-1");
    payload.put("direccion_deudor", "Cdla. Kennedy Norte, Mz. 1 villa 2 → esquina");
    payload.put("estado_civil", "Casada");
    return payload;
  }

  private static String texto(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
      return new PDFTextStripper().getText(doc);
    }
  }

  @Test
  void escribeLoDeLaRemisionYLoQueLlenaLaNotaria() throws Exception {
    byte[] pdf =
        SolicitudExoneracionPdf.llenar(
            payload(),
            "conyuge_2",
            LocalDate.of(2026, 11, 16),
            Map.of("correo", "ana@example.com", "sol_ingreso_1", "USD 1.180,00", "sol_renta", "NO"));

    assertThat(texto(pdf))
        .contains("ANA PÉREZ ÑAUPARI", "0987654321", "15/03/1950") // solicitante: el de 65+
        .contains("LUIS MORA", "0912345678") // va como cónyuge
        .contains("12-0034-005-0-0-0-1", "2026", "ECUATORIANA")
        .contains("ana@example.com", "USD 1.180,00") // lo llenó la notaría
        .contains("Cdla. Kennedy Norte, Mz. 1 villa 2  esquina") // flecha fuera de WinAnsi
        .contains("SOLICITUD DE EXONERACI"); // el formulario original sigue ahí
  }

  @Test
  void loDeLaRemisionSoloEsEditableSiVieneVacio() {
    List<SolicitudExoneracionPdf.Campo> campos =
        SolicitudExoneracionPdf.campos(payload(), "conyuge_2");
    Map<String, SolicitudExoneracionPdf.Campo> porClave =
        campos.stream()
            .collect(java.util.stream.Collectors.toMap(SolicitudExoneracionPdf.Campo::clave, c -> c));

    assertThat(porClave.get("sol_nombre").editable()).isFalse();
    assertThat(porClave.get("correo").editable()).isTrue();
    assertThat(porClave.get("estado_civil").valor()).isEqualTo("CASADO(A)");
    assertThat(porClave.get("estado_civil").editable()).isFalse();
    assertThat(porClave.get("sol_ingresos").tipo()).isEqualTo(SolicitudExoneracionPdf.SI_NO);

    assertThatThrownBy(() -> SolicitudExoneracionPdf.valores(campos, Map.of("sol_nombre", "OTRA")))
        .hasMessageContaining("sol_nombre");
    assertThatThrownBy(() -> SolicitudExoneracionPdf.valores(campos, Map.of("inventado", "x")))
        .hasMessageContaining("inventado");
  }
}
