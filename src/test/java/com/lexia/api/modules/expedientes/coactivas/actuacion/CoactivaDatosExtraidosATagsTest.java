package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.PlantillaDatos;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifica el flujo mapper → binder: claves reales del diagnóstico OCR/IA terminan en los tags
 * DOCX de una plantilla del catálogo (CORRER_TRASLADO usa monto_liquidacion / nombre_deudor_1).
 */
class CoactivaDatosExtraidosATagsTest {

  private static final String RUTA = "templates/coactivas/CORRER_TRASLADO_FACILIDAD_PAGO.docx";
  private static final LocalDate HOY = LocalDate.of(2026, 10, 7);

  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();
  private final CoactivaVariableBinder binder = new CoactivaVariableBinder();

  @Test
  void datosExtraidosRealesResuelvenTagsDocxDelCatalogo() {
    List<String> tags = renderer.tags(RUTA);
    assertTrue(tags.contains("nombre_deudor_1"));
    assertTrue(tags.contains("monto_liquidacion"));
    assertTrue(tags.contains("fecha_liquidacion"));
    assertTrue(tags.contains("numero_juicio"));
    assertTrue(tags.contains("titulo_delegado"));

    Map<String, Object> extraidos = new LinkedHashMap<>();
    extraidos.put("juicio", "025-2024-00026");
    extraidos.put("monto_mora", 15_000);
    extraidos.put("deudor", Map.of("nombre", "JUAN CARLOS PEREZ LOPEZ", "cedula", "1310000001"));
    extraidos.put("garante", Map.of("nombre", "PEDRO GARANTE", "cedula", "1712345678"));
    extraidos.put("liquidacion", Map.of("fecha", "6 de octubre de 2026", "total", 15_000));

    PlantillaDatos datos =
        CoactivaPlantillaDataMapper.construir(
            CoactivaPlantillaDataMapperTest.fuentes(extraidos), CoactivaPlantillaDataMapperTest.AHORA);

    Map<String, String> ia = new LinkedHashMap<>();
    Map<String, String> sistema = new LinkedHashMap<>();
    datos.valores()
        .forEach(
            (tag, valor) -> {
              String texto = CoactivaVariableBinder.limpio(valor == null ? null : valor.toString());
              if (texto == null) {
                return;
              }
              if (CoactivaVariableBinder.esSistema(tag)) {
                sistema.put(tag, texto);
              } else {
                ia.put(tag, texto);
              }
            });

    CoactivaVariableBinder.Resultado r = binder.resolver(tags, ia, sistema, Map.of(), HOY);

    assertTrue(r.liquidacionVigente());
    assertEquals("JUAN CARLOS PEREZ LOPEZ", r.variables().get("nombre_deudor_1"));
    assertEquals("1310000001", r.variables().get("cedula_deudor_1"));
    assertEquals("PEDRO GARANTE", r.variables().get("nombre_garante_1"));
    assertEquals("025-2024-00026", r.variables().get("numero_juicio"));
    assertEquals("15,000.00", r.variables().get("monto_liquidacion"));
    assertEquals("6 de octubre de 2026", r.variables().get("fecha_liquidacion"));
    assertEquals("Delegada", r.variables().get("titulo_delegado"));
    assertEquals("MARIA DELEGADA", r.variables().get("nombre_delegado"));
    assertFalse(r.pendientes().contains("nombre_deudor_1"));
    assertFalse(r.pendientes().contains("monto_liquidacion"));
    assertFalse(r.pendientes().contains("numero_juicio"));
  }
}
