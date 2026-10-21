package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.Config;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.Fuentes;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.PlantillaDatos;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficina;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipante;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoactivaPlantillaDataMapperTest {

  static final UUID TENANT = UUID.randomUUID();
  static final ZonedDateTime AHORA =
      ZonedDateTime.of(2026, 10, 5, 11, 56, 0, 0, ZoneId.of("America/Guayaquil"));

  @Test
  void estructuradoTienePrioridadYSeRegistraLaDiscrepancia() {
    Map<String, Object> ocr = ocrCompleto();
    ocr.put("deudor", "el señor JUAN CARLOS PEREZ LOPEZ");
    ocr.put("cedula_deudor_principal", "0999999999");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);

    assertEquals("JUAN CARLOS PEREZ LOPEZ", datos.valores().get("nombre_deudor_principal"));
    assertEquals("1310000001", datos.valores().get("cedula_deudor_principal"));
    assertEquals(List.of("cedula_deudor_principal"), datos.discrepancias());
    assertEquals("025-2024-00026", datos.valores().get("numero_juicio_coactivo"));
    assertEquals("juan.perez@mail.com", datos.valores().get("correo_notificacion_deudor"));
    assertEquals("MARIA DELEGADA", datos.valores().get("nombre_funcionario_coactiva"));
    assertEquals("BE-GG-2024-001", datos.valores().get("numero_resolucion_delegacion"));
    assertEquals("12 de marzo de 2024", datos.valores().get("fecha_resolucion_delegacion"));
    assertEquals("Portoviejo", datos.valores().get("ciudad_actuacion"));
    assertEquals("4", datos.valores().get("numero_zonal"));
    assertEquals("5 de octubre de 2026", datos.valores().get("fecha_actuacion"));
    assertEquals("11h56", datos.valores().get("hora_actuacion"));
  }

  @Test
  void ocrCompletaLoQueNoEstaEstructurado() {
    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocrCompleto()), AHORA);

    assertEquals("PEDRO GARANTE", datos.valores().get("nombre_garante_solidario"));
    assertEquals("1712345678", datos.valores().get("cedula_garante_solidario"));
    assertEquals("LUIS DEPOSITARIO", datos.valores().get("nombre_depositario_judicial"));
    assertEquals("Banco Pichincha C.A.", datos.valores().get("banco_embargado"));
    assertEquals("ANA GERENTE", datos.valores().get("nombre_gerente_general"));
  }

  @Test
  void cuentasSeAsignanPorTitularYMontosEnLetras() {
    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocrCompleto()), AHORA);
    Map<String, Object> v = datos.valores();

    assertEquals("2200111111", v.get("numero_cuenta_1"));
    assertEquals("2200222222", v.get("numero_cuenta_2"));
    assertEquals("4400333333", v.get("numero_cuenta_3"));
    assertEquals("1,250.50", v.get("monto_embargo_1"));
    assertEquals("MIL DOSCIENTOS CINCUENTA CON 50/100", v.get("monto_embargo_1_letras"));
    assertEquals("300.00", v.get("monto_embargo_2"));
    assertEquals("TRESCIENTOS", v.get("monto_embargo_2_letras"));
    assertEquals("15,000.00", v.get("monto_deuda_total"));
    assertEquals("QUINCE MIL", v.get("monto_deuda_total_letras"));
  }

  @Test
  void sinFuenteLaVariableQuedaFaltanteYNoSeInventa() {
    CoactivaExpediente exp = expediente();
    Fuentes f =
        new Fuentes(exp, List.of(), null, null, null, null, new Config("", "", "", Map.of()));

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(f, AHORA);

    assertFalse(datos.analisisDisponible());
    assertFalse(datos.valores().containsKey("nombre_garante_solidario"));
    assertFalse(datos.valores().containsKey("monto_embargo_1"));
    assertFalse(datos.valores().containsKey("monto_embargo_1_letras"));
    assertTrue(datos.valores().values().stream().noneMatch(x -> x.toString().contains("pendiente")));
    assertEquals(
        List.of("nombre_garante_solidario", "numero_cuenta_1"),
        datos.faltantes(List.of("numero_juicio_coactivo", "nombre_garante_solidario", "numero_cuenta_1")));
  }

  @Test
  void cuentaSinTitularIdentificableNoSeAsigna() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put(
        "cuentas_embargadas",
        List.of(Map.of("numero_cuenta", "999", "monto_retenido", 10), Map.of("numero_cuenta", "888", "titular", "GARANTE SOLIDARIO", "monto_retenido", 5)));

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);

    assertNull(datos.valores().get("numero_cuenta_1"));
    assertEquals("888", datos.valores().get("numero_cuenta_3"));
  }

  @Test
  void titularPorNombreDelParticipante() {
    Fuentes base = fuentes(new LinkedHashMap<>());
    CoactivaParticipante garante =
        CoactivaParticipante.create(TENANT, base.expediente().getId(), "GARANTE", 2, "Pedro Rosales", "MANUAL");
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("cuentas_embargadas", List.of(Map.of("numero_cuenta", "777", "titular", "PEDRO ROSALES", "monto_retenido", 5)));
    Fuentes f =
        new Fuentes(
            base.expediente(),
            List.of(base.participantes().get(0), garante),
            ocr,
            base.oficina(),
            base.delegado(),
            base.secretarioNombre(),
            base.config());

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(f, AHORA);

    assertEquals("777", datos.valores().get("numero_cuenta_3"));
    assertEquals("Pedro Rosales", datos.valores().get("nombre_garante_solidario"));
  }

  @Test
  void aliasCubreEtiquetasDeLasPlantillasNuevas() {
    Map<String, Object> ocr = ocrCompleto();
    ocr.put("fecha_embargo", "27 de abril del 2026");
    ocr.put("fecha_liquidacion", "27 de abril de 2026");
    ocr.put("valor_embargo_num", "155.50");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);
    Map<String, Object> v = datos.valores();

    assertEquals("JUAN CARLOS PEREZ LOPEZ", v.get("nombre_deudor_1"));
    assertEquals("025-2024-00026", v.get("numero_juicio"));
    assertEquals("025-2024-00026", v.get("numero_proceso_coactivo"));
    assertEquals("Portoviejo", v.get("ciudad"));
    assertEquals("4", v.get("numero_zona"));
    assertEquals("PORTOVIEJO", v.get("zona"));
    assertEquals("15,000.00", v.get("monto_liquidacion"));
    assertEquals("CARLOS SECRETARIO", v.get("abogado_secretario"));
    assertEquals("MARIA DELEGADA", v.get("funcionario_coactiva"));
    assertEquals("PEDRO GARANTE", v.get("nombre_garante_1"));
    assertEquals("1", v.get("foja_inicio_numero"));
    assertEquals("120", v.get("foja_fin_numero"));
    assertEquals("ciento veinte", v.get("foja_fin_texto"));
    assertEquals("Banecuador B.P.", v.get("nombre_institucion_bancaria"));
    assertEquals("5", v.get("dia_entrega"));
    assertEquals("octubre", v.get("mes_entrega"));
    assertEquals("2026", v.get("anio_entrega"));
    assertEquals("27", v.get("dia_embargo"));
    assertEquals("abril", v.get("mes_embargo"));
    assertEquals("2026", v.get("anio_embargo"));
    assertEquals("27 de abril de 2026", v.get("fecha_liquidacion_actualizada"));
    assertEquals("2200111111", v.get("numero_cuenta_retencion"));
    assertEquals("172.50", v.get("valor_honorarios_total_num"));
    assertEquals("150.00", v.get("valor_honorarios_subtotal"));
    assertEquals("22.50", v.get("valor_iva_honorarios"));
    assertEquals("CIENTO CINCUENTA Y CINCO CON 50/100", v.get("valor_embargo_texto"));
    assertEquals("JUAN CARLOS PEREZ LOPEZ", v.get("nombre_receptor"));
  }

  @Test
  void limpiaTratamientosDeLosNombres() {
    assertEquals("Juan Pérez", CoactivaPlantillaDataMapper.limpiarNombre("el/la señor(a) Juan Pérez"));
    assertEquals("Ana Ruiz", CoactivaPlantillaDataMapper.limpiarNombre("Abg. Ana Ruiz"));
    assertEquals("ANA GERENTE", CoactivaPlantillaDataMapper.limpiarNombre("Mgs. ANA GERENTE"));
    assertEquals("Rosa Mena", CoactivaPlantillaDataMapper.limpiarNombre("Sra. Rosa Mena"));
    assertNull(CoactivaPlantillaDataMapper.limpiarNombre("  "));
  }

  @Test
  void montosInvalidosNoSeUsan() {
    assertNull(CoactivaPlantillaDataMapper.monto("no consta"));
    assertNull(CoactivaPlantillaDataMapper.monto(0));
    assertEquals(new BigDecimal("1234.50"), CoactivaPlantillaDataMapper.monto("$ 1.234,50"));
  }

  // ---------------------------------------------------------------------------

  static Fuentes fuentes(Map<String, Object> ocr) {
    CoactivaExpediente exp = expediente();
    CoactivaParticipante deudor =
        CoactivaParticipante.create(TENANT, exp.getId(), "DEUDOR", 1, "JUAN CARLOS PEREZ LOPEZ", "MANUAL");
    deudor.update(
        "DEUDOR", 1, "NATURAL", "CEDULA", "1310000001", "JUAN CARLOS PEREZ LOPEZ",
        "Juan.Perez@mail.com; otro@mail.com", null, null, true);
    CoactivaDelegado delegado = CoactivaDelegado.create(TENANT, "MARIA DELEGADA", null);
    delegado.update(
        "MARIA DELEGADA", null, "Delegada", "BE-GG-2024-001", LocalDate.of(2024, 3, 12),
        null, null, "maria.delegada@banecuador.fin.ec", true, null);
    CoactivaOficina oficina = CoactivaOficina.create(TENANT, "PORTOVIEJO", "Portoviejo", "Manabí", 1);
    return new Fuentes(
        exp,
        List.of(deudor),
        ocr,
        oficina,
        delegado,
        "CARLOS SECRETARIO",
        new Config(
            "",
            "coactiva@banecuador.fin.ec",
            "estudio@juridico.ec",
            CoactivaPlantillaDataMapper.parsearZonales("PORTOVIEJO=4")));
  }

  static CoactivaExpediente expediente() {
    CoactivaExpediente exp = CoactivaExpediente.create(TENANT, UUID.randomUUID(), "025-2024-00026", null);
    exp.setDatosBase("OP-778899", 2024, "PORTOVIEJO", 120);
    return exp;
  }

  static Map<String, Object> ocrCompleto() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("juicio", "25-2024-26");
    ocr.put("operacion", "OP-778899");
    ocr.put("deudor", "JUAN CARLOS PEREZ LOPEZ");
    ocr.put("nombre_garante_solidario", "el señor PEDRO GARANTE");
    ocr.put("cedula_garante_solidario", "171234567-8");
    ocr.put("nombre_depositario_judicial", "LUIS DEPOSITARIO");
    ocr.put("cedula_depositario_judicial", "0912345678");
    ocr.put("nombre_gerente_general", "Mgs. ANA GERENTE");
    ocr.put("monto_deuda_total", 15000);
    ocr.put("monto_honorarios", "172.50");
    ocr.put("cuenta_honorarios_abogado", "3001234567");
    ocr.put("banco_embargado", "Banco Pichincha C.A.");
    ocr.put(
        "cuentas_embargadas",
        List.of(
            Map.of("numero_cuenta", "2200111111", "tipo", "CORRIENTE", "titular", "DEUDOR", "monto_retenido", 1250.5),
            Map.of("numero_cuenta", "2200222222", "tipo", "CORRIENTE", "titular", "DEUDOR", "monto_retenido", "300"),
            Map.of("numero_cuenta", "4400333333", "tipo", "AHORROS", "titular", "GARANTE", "monto_retenido", 80)));
    return ocr;
  }
}
